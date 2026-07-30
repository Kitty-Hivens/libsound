// libsound Phase 0 spike, second half -- throwaway.
//
// The pulse spike measured what libpulse does around a cork and a flush. This
// measures the same things on JavaSound, because the fallback is not a
// hypothetical: on this desktop it lands in the same PipeWire graph through
// ALSA, so the two backends differ by API, not by device, and any difference in
// the numbers is the API's doing.
//
//   java JavaSoundProbe.java

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

public class JavaSoundProbe {

    static final int RATE = 48_000;
    static final int FRAME = 4;                  // S16LE stereo
    static final int BUFFER_FRAMES = RATE / 5;   // 200 ms, skinema's floor

    public static void main(String[] args) throws Exception {
        AudioFormat format = new AudioFormat(RATE, 16, 2, true, false);
        SourceDataLine line = AudioSystem.getSourceDataLine(format);
        System.out.println("line: " + line.getClass().getName());

        // 1. Does open() start the device?
        //
        // The contract says it must. JavaSound splits the two, so this measures
        // the gap the adapter has to close rather than assuming it.
        line.open(format, BUFFER_FRAMES * FRAME);
        System.out.println("after open, isActive=" + line.isActive()
                + " isRunning=" + line.isRunning()
                + " pos=" + line.getLongFramePosition());

        byte[] silence = new byte[BUFFER_FRAMES * FRAME];
        line.write(silence, 0, silence.length);
        Thread.sleep(150);
        System.out.println("after a write with no start(), pos=" + line.getLongFramePosition()
                + "  <- nonzero here would mean open() started it");

        // The position does not necessarily begin moving when the device does.
        // Sampling it densely turns "the first reading looked wrong" into a
        // number the adapter can be written against.
        line.start();
        long startWall = System.nanoTime();
        long firstMovementMillis = -1;
        for (int i = 0; i < 100; i++) {
            if (line.getLongFramePosition() > 0) {
                firstMovementMillis = (System.nanoTime() - startWall) / 1_000_000;
                break;
            }
            Thread.sleep(10);
        }
        System.out.println("position first moved " + firstMovementMillis
                + " ms after start()  <- a clock reading it before this sees a stopped device");
        Thread.sleep(250);
        long afterStart = line.getLongFramePosition();
        System.out.println("after start() + 250 ms, pos=" + afterStart);

        // 2. Does stop() freeze the position?
        line.write(silence, 0, silence.length);
        Thread.sleep(50);
        line.stop();
        long frozen = line.getLongFramePosition();
        Thread.sleep(250);
        long stillFrozen = line.getLongFramePosition();
        System.out.println("stop(): " + frozen + " -> " + stillFrozen
                + "  (delta " + (stillFrozen - frozen) + ")");

        // 3. What does flush() do to the position?
        //
        // This is the one the clock's monotonic clamp exists for. The frames in
        // the buffer were never rendered, so a position that counted them would
        // have to give them back -- and giving them back is a position moving
        // backwards under a mastered clock.
        long beforeFlush = line.getLongFramePosition();
        line.flush();
        long afterFlush = line.getLongFramePosition();
        System.out.println("flush(): " + beforeFlush + " -> " + afterFlush
                + "  (delta " + (afterFlush - beforeFlush) + ")");

        // 4. And across a restart, which is the other half of a seek.
        line.start();
        line.write(silence, 0, silence.length);
        Thread.sleep(250);
        long afterRestart = line.getLongFramePosition();
        System.out.println("after restart + 250 ms, pos=" + afterRestart
                + "  (delta from flush " + (afterRestart - afterFlush) + ")");

        // 5. Monotonicity across a whole seek-shaped sequence, sampled tightly.
        //
        // A single before/after pair can step over a transient. The clamp in the
        // clock exists for a dip that lasts one reading, so the sampling has to
        // be tighter than the transient.
        long worstDip = 0;
        long previous = line.getLongFramePosition();
        for (int round = 0; round < 5; round++) {
            line.write(silence, 0, silence.length);
            line.stop();
            line.flush();
            line.start();
            for (int i = 0; i < 200; i++) {
                long now = line.getLongFramePosition();
                if (now < previous) worstDip = Math.min(worstDip, now - previous);
                previous = now;
                Thread.sleep(1);
            }
        }
        System.out.println("worst backwards step over 5 stop/flush/start rounds: " + worstDip + " frames");

        // 6. How coarsely does the position actually advance?
        //
        // The decisive measurement for the fallback's DEVICE_POSITION claim. A
        // clock wants a playhead that tracks the device; one that stands still
        // and then leaps by a period is a clock that stutters by the size of
        // that leap, whatever the average rate looks like.
        line.stop();
        line.flush();
        line.start();
        final SourceDataLine feeding = line;
        Thread feeder = new Thread(() -> {
            byte[] chunk = new byte[RATE / 50 * FRAME];   // 20 ms
            for (int i = 0; i < 100 && !Thread.currentThread().isInterrupted(); i++) {
                feeding.write(chunk, 0, chunk.length);
            }
        }, "probe-feeder");
        feeder.setDaemon(true);
        feeder.start();

        long base = line.getLongFramePosition();
        long previousSample = base;
        long maxStep = 0;
        int standingStill = 0;
        int samples = 0;
        long wallStart = System.nanoTime();
        while (System.nanoTime() - wallStart < 1_500_000_000L) {
            long now = line.getLongFramePosition();
            long step = now - previousSample;
            if (step == 0) standingStill++;
            maxStep = Math.max(maxStep, step);
            previousSample = now;
            samples++;
            Thread.sleep(10);
        }
        long travelled = previousSample - base;
        long wallMillis = (System.nanoTime() - wallStart) / 1_000_000;
        System.out.println("over " + wallMillis + " ms of continuous feeding:");
        System.out.println("  frames advanced   = " + travelled
                + " (" + (travelled * 1000 / RATE) + " ms of audio)");
        System.out.println("  largest single step = " + maxStep + " frames ("
                + (maxStep * 1000 / RATE) + " ms) sampled every 10 ms");
        System.out.println("  readings where it did not move = " + standingStill + "/" + samples);
        feeder.interrupt();

        // 6. What the fallback cannot do, stated rather than assumed.
        System.out.println("MASTER_GAIN supported: "
                + line.isControlSupported(javax.sound.sampled.FloatControl.Type.MASTER_GAIN));

        line.stop();
        line.close();
        System.out.println("done");
    }
}
