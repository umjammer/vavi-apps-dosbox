package jdos.win.builtin.user32;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import javax.swing.JOptionPane;

import jdos.win.builtin.WinAPI;
import jdos.win.utils.StringUtil;


public class MsgBox extends WinAPI {

    private static final Logger logger = System.getLogger(MsgBox.class.getName());

    // int WINAPI MessageBox(HWND hWnd, LPCTSTR lpText, LPCTSTR lpCaption, UINT uType)
    static public int MessageBoxA(int hWnd, int lpText, int lpCaption, int uType) {
        String text = StringUtil.getString(lpText);
        String caption = "";
        if (lpCaption != 0)
            caption = StringUtil.getString(lpCaption);
        traceUi("MessageBoxA caption=" + caption + " text=" + text);
        // the icon is one value in the low nibble of the high half, not a set of bits: tested
        // with masks, 0x10 (error) also matches 0x30 (warning) and every error came out as one
        int type = switch (uType & 0xF0) {
            case 0x10 -> JOptionPane.ERROR_MESSAGE;
            case 0x20 -> JOptionPane.QUESTION_MESSAGE;
            case 0x30 -> JOptionPane.WARNING_MESSAGE;
            default -> JOptionPane.INFORMATION_MESSAGE;
        };
        // said out loud whatever it is: a machine with nothing drawn cannot show the box, and
        // what is in it is usually the only thing the program will ever say about what went
        // wrong - FMP7 puts the file it could not load in one and then throws
        logger.log(Level.WARNING, "the guest says: " + caption + ": " + text);
        //JOptionPane.showMessageDialog(null, text, caption, type);
        return IDOK;
    }
}
