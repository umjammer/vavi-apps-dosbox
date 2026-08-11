package jdos.api;

/**
 * Somewhere for emulated audio to go other than the host's speakers.
 * <p>
 * jdosbox has two independent outputs - the DOSBox mixer (Sound Blaster, AdLib, PC speaker,
 * ...) and the winmm {@code waveOut} device that a Win32 guest program writes to. Either can
 * be handed a sink instead of a {@code SourceDataLine}; see
 * {@link JDosBox#mixerSink} and {@link JDosBox#waveOutSink}.
 * <p>
 * The samples are signed little-endian PCM, whatever the guest asked for (8 bit unsigned is
 * converted before it gets here, so a sink never sees it).
 * <p>
 * <b>{@link #write} paces the emulation.</b> A sink that blocks until it has room is what
 * keeps a {@link JDosBox#turbo turbo} machine - which is otherwise bounded by nothing but the
 * host's speed - producing audio at exactly the rate the consumer takes it, without relying on
 * the host clock being precise. A sink that never blocks lets the machine run flat out, which
 * is what rendering to a file wants.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-10 nsano initial version <br>
 */
public interface AudioSink {

    /** the guest opened an output; the format is the one it asked for */
    void open(int sampleRate, int sampleSizeInBits, int channels);

    /**
     * Takes one buffer's worth of samples. Blocking here is what throttles the emulation,
     * so a sink that means to pace the machine must not drop data instead of waiting.
     */
    void write(byte[] data, int offset, int length);

    /** the guest closed the output, or the program that opened it exited */
    void close();
}
