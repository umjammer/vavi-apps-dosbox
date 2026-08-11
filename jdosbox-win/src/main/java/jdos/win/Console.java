package jdos.win;

import java.nio.charset.StandardCharsets;

import jdos.api.JDosBox;
import jdos.api.StdioSink;
import jdos.win.builtin.winmm.Waveform;

/**
 * The machine's console.
 * <p>
 * Two different things end up here and they are kept apart: {@link #write} is the guest program
 * talking, which an embedding program can take for itself with {@link JDosBox#stdioSink}, and
 * {@link #out} is jdosbox saying something about the guest, which always goes to the host's own
 * console so that it cannot be mistaken for the program's output.
 */
public class Console {

    /** jdosbox's own diagnostics about the guest */
    static public void out(String msg) {
        System.out.println(msg);
    }

    /** what the guest program wrote to its {@code stdout} or {@code stderr} */
    static public void write(String msg) {
        StdioSink sink = JDosBox.getStdioSink();
        if (sink == null) {
            System.out.print(msg);
            return;
        }
        byte[] bytes = msg.getBytes(StandardCharsets.ISO_8859_1);
        write(bytes, 0, bytes.length);
    }

    /** the same, for a guest that writes bytes rather than going through its C runtime */
    static public void write(byte[] data, int offset, int length) {
        StdioSink sink = JDosBox.getStdioSink();
        if (sink == null) {
            System.out.print(new String(data, offset, length, StandardCharsets.ISO_8859_1));
            return;
        }
        // stamped with the audio the guest had produced by now, which is how the host lines what
        // it says up with the sound it is saying it about - see StdioSink
        sink.write(data, offset, length, Waveform.producedFrames());
    }
}
