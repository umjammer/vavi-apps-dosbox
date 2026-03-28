package jdos.win.builtin.gdi32;

import junit.framework.TestCase;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import jdos.win.builtin.WinAPI;

public class testBitBlt extends TestCase {
    public void testStretchBlt2D() {
        BufferedImage dest = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        BufferedImage src = new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB);
        
        Graphics2D srcG = src.createGraphics();
        srcG.setColor(java.awt.Color.RED);
        srcG.fillRect(0, 0, 50, 50);
        srcG.dispose();

        Graphics2D destG = dest.createGraphics();
        // Draw the 50x50 red src at offset 10,10 in dest
        BitBlt.StretchBlt2D(destG, 10, 10, 50, 50, src, 0, 0, 50, 50, WinAPI.SRCCOPY);
        destG.dispose();

        // Verify colors
        assertEquals(java.awt.Color.RED.getRGB(), dest.getRGB(10, 10));
        assertEquals(java.awt.Color.RED.getRGB(), dest.getRGB(59, 59));
        
        // Ensure outside is not red
        assertEquals(0, dest.getRGB(9, 9));
        assertEquals(0, dest.getRGB(60, 60));
    }
}
