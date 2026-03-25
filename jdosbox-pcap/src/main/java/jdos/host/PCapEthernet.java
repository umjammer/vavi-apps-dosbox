package jdos.host;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;

import jdos.misc.setup.Section_prop;
import jdos.util.Ptr;
import org.jnetpcap.Pcap;
import org.jnetpcap.PcapHeader;
import org.jnetpcap.PcapIf;
import org.jnetpcap.nio.JBuffer;
import org.jnetpcap.nio.JMemory;

public class PCapEthernet implements Ethernet{

    private static final Logger logger = System.getLogger(PCapEthernet.class.getName());

    Pcap pcap;

    @Override
    public void send(byte[] buffer, int offset, int len) {
        pcap.sendPacket(buffer, offset, len);
    }

    @Override
    public void receive(RxFrame frame) {
        PcapHeader header = new PcapHeader(JMemory.POINTER);
        JBuffer buffer = new JBuffer(JMemory.POINTER);
        while (pcap.nextEx(header, buffer) == Pcap.NEXT_EX_OK) {
            byte[] data = buffer.getByteArray(0, header.hdr_len());
            if (!frame.rx_frame(new Ptr(data, 0),header.hdr_len()))
                return;
        }
    }

    @Override
    public void close() {
        if (pcap!=null) {
            pcap.close();
            pcap = null;
        }
    }
    @Override
    public boolean open(Section_prop section, byte[] mac) {
        pcap = open(section.Get_string("realnic"), true);
        return pcap!=null;
    }
    static public Pcap open(String realnicstring, boolean async) {
        try {
            List<PcapIf> alldevs = new ArrayList<>(); // Will be filled with NICs
            StringBuilder errbuf = new StringBuilder(); // For any error msgs

            //
            // First get a list of devices on this system
            //
            int r = Pcap.findAllDevs(alldevs, errbuf);
            if (r == Pcap.NOT_OK || alldevs.isEmpty()) {
                logger.log(Level.DEBUG, "Cannot enumerate network interfaces: " + errbuf);
                return null;
            }

            if (realnicstring.equalsIgnoreCase("list")) {
                int i = 0;
                logger.log(Level.DEBUG, "\nNetwork Interface List \n-----------------------------------");
                for (i=0;i<alldevs.size();i++) {
                    PcapIf currentdev = alldevs.get(i);
                    String desc = currentdev.getDescription();
                    if (desc == null || desc.isEmpty())
                        desc = "no description";
                    i++;
                    logger.log(Level.DEBUG, "%2d. %s\n    (%s)\n".formatted(i, currentdev.getName(), desc));
                }
                Pcap.freeAllDevs(alldevs, errbuf);
                return null;
            }
            PcapIf dev = null;
            try {
                int index = Integer.parseInt(realnicstring);
                if (index>=0 && index<=alldevs.size()) {
                    dev = alldevs.get(index);
                }
            } catch (Exception e) {
                for (PcapIf currentdev : alldevs) {
                    if (currentdev.getName().contains(realnicstring)) {
                        dev = currentdev;
                        break;
                    } else if (currentdev.getDescription() != null && currentdev.getDescription().contains(realnicstring)) {
                        dev = currentdev;
                        break;
                    }
                }
            }
            if (dev == null) {
                logger.log(Level.DEBUG, "Unable to find network interface - check realnic parameter\n");
                Pcap.freeAllDevs(alldevs, errbuf);
                return null;
            }
            String desc = dev.getDescription();
            if (desc == null || desc.isEmpty())
                desc = "no description";
    		logger.log(Level.DEBUG, "Using Network interface:\n" + dev.getName() + "\n(" + desc + ")\n");
            Pcap pcap = Pcap.openLive(dev.getName(), 65536, Pcap.MODE_PROMISCUOUS, -1, errbuf);
            if (pcap == null) {
                logger.log(Level.DEBUG, "\\nUnable to open the interface: " + errbuf);
                Pcap.freeAllDevs(alldevs, errbuf);
                return null;
            }
            if (async)
                pcap.setNonBlock(1, errbuf);
            return pcap;
        } catch (Throwable e) {
            logger.log(Level.ERROR, e.getMessage(), e);
            return null;
        }
    }
}
