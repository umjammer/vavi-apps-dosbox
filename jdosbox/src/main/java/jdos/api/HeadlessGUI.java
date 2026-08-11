package jdos.api;

import jdos.sdl.GUI;


/**
 * A {@link GUI} that draws nothing, for a machine that is run for its side effects - a program's
 * output, or its audio - rather than to be looked at.
 * <p>
 * The VGA emulation still runs and still fills {@link jdos.gui.Main#buffer2}, so a caller that
 * wants to know what is on the screen can still read it; nothing is ever painted.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-10 nsano initial version <br>
 */
public class HeadlessGUI implements GUI {

    /** what the emulated screen would be, had anyone been shown it */
    public int width;
    /** @see #width */
    public int height;

    /** the last title the machine set, which is where DOSBox puts its cycle count */
    public String title;

    @Override
    public void setSize(int cx, int cy) {
        this.width = cx;
        this.height = cy;
    }

    @Override
    public void dopaint() {
    }

    @Override
    public void showProgress(String msg, int percent) {
    }

    @Override
    public void setTitle(String title) {
        this.title = title;
    }

    @Override
    public void showCursor(boolean on) {
    }

    @Override
    public void captureMouse(boolean on) {
    }

    @Override
    public void fullScreenToggle() {
    }
}
