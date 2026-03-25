package jdos.host.router;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;


public class ICMP extends EtherUtil {

    private static final Logger logger = System.getLogger(ICMP.class.getName());

    private void parse(byte[] buffer, int offset) {
        type = buffer[offset] & 0xFF;
        code = buffer[offset+1] & 0xFF;
        checksum = (short)readWord(buffer, offset+2);
    }

    public void handle(byte[] buffer, int offset, int len) {
        System.out.print("Received ICMP Packet ");
        parse(buffer, offset);
        if (type == 0) {
            logger.log(Level.DEBUG," PING");
        } else {
            String strType = switch (type) {
                case 3 -> "Destination Unreachable";
                case 4 -> "Source Quench";
                case 5 -> "Redirect Message";
                case 8 -> "Echo Request";
                case 9 -> "Router Advertisement";
                case 10 -> "Router Solicitation";
                case 11 -> "Time Exceeded";
                case 12 -> "Parameter Problem: Bad IP header";
                case 13 -> "Timestamp";
                case 14 -> "Timestamp Reply";
                case 15 -> "Information Request";
                case 16 -> "Information Reply";
                case 17 -> "Address Mask Request";
                case 18 -> "Address Mask Reply";
                case 30 -> "Traceroute";
                default -> null;
            };

            System.out.print(" type="+type);
            if (strType != null)
                System.out.print("("+strType+")");
            logger.log(Level.DEBUG," code="+code);
        }
    }

    public int type;
    public int code;
    public short checksum;
}
