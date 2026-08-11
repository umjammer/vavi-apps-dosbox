package jdos.api;

/**
 * Somewhere for a guest program's own console output to go other than the host's stdout.
 * <p>
 * This is what a win32 guest writes to {@code stdout} or {@code stderr} - through
 * {@code WriteFile} on the standard handles, or through the C runtime's {@code printf} family -
 * and nothing else. jdosbox's own diagnostics keep going to the host's console, so a program can
 * be given a channel out of the machine without the emulator talking over it. A DOS program
 * writing through int 21h does not come this way; it is the win32 layer that is tapped.
 * <p>
 * <b>Why the frame count.</b> A machine used as an audio source runs ahead of what is being
 * heard - the consumer's queue is what paces it, and that queue is deliberately seconds deep - so
 * a line the guest prints describes a moment that has not been played yet. Handed on its own it
 * would be seconds early. So each chunk is stamped with how much audio the guest had produced
 * when it wrote it, in the same numbering the {@link AudioSink} counts its own frames in, and the
 * host can line the two up.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-11 nsano initial version <br>
 * @see JDosBox#stdioSink
 */
public interface StdioSink {

    /**
     * Takes what the guest wrote. Called from whichever guest thread wrote it, so a sink that
     * does real work with it should hand it on rather than block: this is the cpu the machine
     * needs to keep its audio coming.
     *
     * @param frames how much audio the guest had produced when it wrote this, counted in frames
     *               of the {@link AudioSink} stream; -1 when no {@code waveOut} device is open
     */
    void write(byte[] data, int offset, int length, long frames);
}
