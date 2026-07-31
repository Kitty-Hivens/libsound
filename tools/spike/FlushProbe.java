import javax.sound.sampled.*;
public class FlushProbe {
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
        System.out.println("raw before flush=" + b + " after=" + c + " credit=" + credit + " reported afterFlush=" + afterFlush);
        l.start();
        int fed = RATE/4;
        byte[] quarter = new byte[fed*FRAME];
        l.write(quarter, 0, quarter.length);
        Thread.sleep(fed*1000L/RATE + 150);
        long raw = l.getLongFramePosition();
        long reported = Math.max(0, raw - credit);
        System.out.println("fed=" + fed + " raw=" + raw + " reported=" + reported + " moved=" + (reported - afterFlush));
        System.out.println("need moved > " + (fed*0.4) + " and < " + (fed*1.8));
        l.stop(); l.close();
    }
}
