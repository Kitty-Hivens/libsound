import javax.sound.sampled.*;
public class FlushProbe2 {
    static final int RATE = 48000, FRAME = 4;
    public static void main(String[] a) throws Exception {
        AudioFormat f = new AudioFormat(RATE, 16, 2, true, false);
        SourceDataLine l = AudioSystem.getSourceDataLine(f);
        l.open(f, (RATE/5)*FRAME); l.start();
        long credit = 0;
        byte[] half = new byte[RATE/2*FRAME];
        l.write(half, 0, half.length);
        l.stop();
        long b = l.getLongFramePosition(); l.flush(); long c = l.getLongFramePosition();
        if (c > b) credit += c - b;
        long afterFlush = Math.max(0, l.getLongFramePosition() - credit);
        l.start();
        // continuous feed, 20 ms chunks, half a second total
        byte[] chunk = new byte[RATE/50*FRAME];
        long fed = 0;
        for (int i = 0; i < 25; i++) { l.write(chunk, 0, chunk.length); fed += RATE/50; }
        Thread.sleep(fed*1000L/RATE + 150);
        long reported = Math.max(0, l.getLongFramePosition() - credit);
        long moved = reported - afterFlush;
        System.out.println("afterFlush=" + afterFlush + " fed=" + fed + " moved=" + moved
            + "  ratio=" + String.format("%.2f", moved / (double) fed));
        System.out.println("window 0.4..1.8 -> " + (moved > fed*0.4 && moved < fed*1.8 ? "PASS" : "FAIL"));
        l.stop(); l.close();
    }
}
