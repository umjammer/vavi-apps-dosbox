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
        int type = JOptionPane.INFORMATION_MESSAGE;
        if ((uType & 0x00000040) != 0) // MB_ICONINFORMATION
            type = JOptionPane.INFORMATION_MESSAGE;
        else if ((uType & 0x00000030) != 0) // MB_ICONWARNING
            type = JOptionPane.WARNING_MESSAGE;
        else if ((uType & 0x00000020) != 0) // MB_ICONQUESTION
            type = JOptionPane.QUESTION_MESSAGE;
        else if ((uType & 0x00000010) != 0) // MB_ICONERROR
            type = JOptionPane.ERROR_MESSAGE;
        logger.log(Level.TRACE, "MessageBoxA caption=" + caption + " text=" + text);
        //JOptionPane.showMessageDialog(null, text, caption, type);
        return IDOK;
    }
}
