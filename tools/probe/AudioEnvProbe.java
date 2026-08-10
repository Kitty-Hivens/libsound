// What audio, if any, exists on the machine running this.
//
//   java AudioEnvProbe.java
//
// Written for CI runners, where the answer decides how much of the test suite
// can mean anything. A runner with no output device turns every hardware suite
// into a skip, and a skip that nobody notices reads exactly like a pass -- which
// is the failure LIBSOUND_REQUIRE exists to prevent. Before the gate can name a
// backend on a given row, somebody has to know whether that row could ever
// satisfy it.

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

public class AudioEnvProbe {

    public static void main(String[] args) {
        System.out.println("os.name    = " + System.getProperty("os.name"));
        System.out.println("os.arch    = " + System.getProperty("os.arch"));
        System.out.println("java       = " + System.getProperty("java.version"));

        AudioFormat format = new AudioFormat(48_000f, 16, 2, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

        System.out.println("\n-- mixers offering a 48k stereo output line --");
        int offering = 0;
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            if (mixer.isLineSupported(info)) {
                offering++;
                System.out.printf("   %-30s | %s%n", mixerInfo.getName(), mixerInfo.getDescription());
            }
        }
        if (offering == 0) System.out.println("   (none)");

        // isLineSupported is what the test gate asks, but a line that is
        // supported and cannot be opened is the case that fails a suite rather
        // than skipping it -- so ask the harder question too.
        System.out.println("\n-- can a line actually open --");
        System.out.println("   isLineSupported = " + AudioSystem.isLineSupported(info));
        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format, 8192);
            line.start();
            System.out.println("   opened          = yes, buffer " + line.getBufferSize() + " bytes");
            line.stop();
        } catch (Throwable t) {
            System.out.println("   opened          = no: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
