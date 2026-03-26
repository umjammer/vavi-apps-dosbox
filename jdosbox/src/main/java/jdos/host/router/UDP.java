package jdos.host.router;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;


public class UDP extends EtherUtil {

    private static final Logger logger = System.getLogger(UDP.class.getName());

    static final public int LEN = 8;

    private void parse(byte[] buffer, int offset) {
        sourcePort = readWord(buffer, offset);
        destPort = readWord(buffer, offset + 2);
        len = readWord(buffer, offset + 4);
        sum = (short) readWord(buffer, offset + 6);
    }

    int sourcePort;
    int destPort;
    int len;
    short sum;

    static final private byte[] tmp = new byte[2048];

    static private short udp_csum(int sourceAddress, int destAddress, byte[] buffer, int offset, int count) {
        /*
        int total = ~csum(buffer, offset, count) + (sourceAddress & 0xFFFF) + ((sourceAddress >>> 16) & 0xFFFF) + (destAddress & 0xFFFF) + ((destAddress >>> 16) & 0xFFFF) + count + 17;
        int carry;
        while ((carry = total >>> 16)>0) {
            total = (total & 0xFFFF)+carry;
        }
        return (short)~total;
        */
        // psudo header
        writeDWord(tmp, 0, sourceAddress);
        writeDWord(tmp, 4, destAddress);
        tmp[8] = 0;
        tmp[9] = 17;
        writeWord(tmp, 10, count);

        System.arraycopy(buffer, offset, tmp, 12, count);
        return csum(tmp, 0, count + 12);
    }

    public static void output(int sourceAddress, int destAddress, byte[] buffer, int offset, int sourcePort, int destPort, int payloadLen) {
        writeWord(buffer, offset, sourcePort);
        writeWord(buffer, offset + 2, destPort);
        writeWord(buffer, offset + 4, payloadLen + UDP.LEN);
        writeWord(buffer, offset + 6, 0);
        int sum = udp_csum(sourceAddress, destAddress, buffer, offset, payloadLen + UDP.LEN);
        if (sum == 0)
            sum = 0xFFFF;
        writeWord(buffer, offset + 6, sum);
    }

    public void handle(byte[] buffer, int offset, int len) {
        System.out.print("Received UDP Packet");
        parse(buffer, offset);
        //dump(buffer, offset, len);
        if (sum == 0 || UDP.udp_csum(Ether.ip.sourceIP, Ether.ip.destIP, buffer, offset, len) == 0) {
            if (destPort == 67) { // BOOTP/DHCP
                logger.log(Level.DEBUG, "");
                bootp.handle(buffer, offset + LEN, len - LEN);
                logger.log(Level.DEBUG, "");
            } else {
                System.out.print(" ");
                printAddress(Ether.ip.destIP);
                logger.log(Level.DEBUG, ":" + destPort);
            }
        } else {
            logger.log(Level.DEBUG, " Bad Checksum");
        }
    }

    private final BootP bootp = new BootP();
}
