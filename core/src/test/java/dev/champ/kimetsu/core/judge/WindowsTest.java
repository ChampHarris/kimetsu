package dev.champ.kimetsu.core.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The jitter and RTT windows behind the stamp tolerance (Rule 2.1). */
class WindowsTest {

    @Test
    void jitterIsTheSampleDeviationOfTheLag() {
        JitterWindow w = new JitterWindow(40);
        assertEquals(0, w.sdMicros());
        w.add(1_075_000, 1_000_000);
        assertEquals(0, w.sdMicros()); // one frame: no deviation yet
        w.add(1_135_000, 1_050_000); // lags 75 and 85 ms
        assertEquals(7071.07, w.sdMicros(), 0.01);
    }

    @Test
    void jitterKeepsOnlyTheLastFrames() {
        JitterWindow w = new JitterWindow(40);
        for (int k = 0; k < 40; k++) {
            w.add(k * 50_000L + (k % 2 == 0 ? 0 : 100_000), k * 50_000L);
        }
        for (int k = 40; k < 80; k++) {
            w.add(k * 50_000L + 75_000, k * 50_000L); // steady again: the old spread has rolled out
        }
        assertEquals(40, w.count());
        assertEquals(0, w.sdMicros(), 1e-9);
    }

    @Test
    void aConstantDelayIsNoJitterHoweverLong() {
        JitterWindow w = new JitterWindow(40);
        for (int k = 0; k < 40; k++) {
            w.add(k * 50_000L + 400_000, k * 50_000L);
        }
        assertEquals(0, w.sdMicros(), 1e-9);
    }

    private static final long RISE = 40_000; // twice the 20 ms tolerance floor, as the intake sets it

    @Test
    void rttIsTheMinimumOverTheLastTenSeconds() {
        RttWindow r = new RttWindow(10_000_000, RISE);
        assertEquals(0, r.minMicros(0));
        r.add(1_000_000, 150_000);
        r.add(2_000_000, 120_000);
        r.add(3_000_000, 180_000);
        assertEquals(120_000, r.minMicros(3_000_000));
        assertEquals(120_000, r.minMicros(12_000_000)); // 2 s + 10 s: still inside
        assertEquals(180_000, r.minMicros(12_000_001)); // the 120 has aged out
    }

    @Test
    void withNoRecentEchoTheNewestStands() {
        RttWindow r = new RttWindow(10_000_000, RISE);
        r.add(1_000_000, 150_000);
        r.add(2_000_000, 160_000);
        assertEquals(160_000, r.minMicros(60_000_000));
    }

    /** Bufferbloat: the minimum alone would hold ref 65 ms early for 10 s, failing every honest frame (netcode review). */
    @Test
    void aSustainedRiseIsFollowedAfterTwoEchoes_aLoneSpikeIsNot_aDropAtOnce() {
        RttWindow r = new RttWindow(10_000_000, RISE);
        r.add(1_000_000, 20_000);
        r.add(2_000_000, 21_000);
        r.add(3_000_000, 150_000);
        assertEquals(20_000, r.minMicros(3_000_000)); // one spike
        r.add(4_000_000, 152_000);
        assertEquals(150_000, r.minMicros(4_000_000)); // two in a row: the rise is real
        r.add(5_000_000, 20_000);
        assertEquals(20_000, r.minMicros(5_000_000)); // a drop needs no proof: the minimum takes it

        RttWindow spiky = new RttWindow(10_000_000, RISE);
        spiky.add(1_000_000, 20_000);
        spiky.add(2_000_000, 150_000);
        spiky.add(3_000_000, 22_000);
        spiky.add(4_000_000, 150_000);
        assertEquals(20_000, spiky.minMicros(4_000_000)); // spikes apart are jitter
        RttWindow small = new RttWindow(10_000_000, RISE);
        small.add(1_000_000, 20_000);
        small.add(2_000_000, 55_000);
        small.add(3_000_000, 55_000);
        assertEquals(20_000, small.minMicros(3_000_000)); // within the tolerance floor: the minimum stands
    }

    @Test
    void aRiseFollowedAfterTheRingWrapped_keepsOnlyTheRise() {
        RttWindow r = new RttWindow(100_000_000, RISE);
        for (int i = 0; i < 40; i++) {
            r.add(i * 1_000_000L, 20_000);
        }
        r.add(40_000_000, 150_000);
        r.add(41_000_000, 160_000);
        assertEquals(150_000, r.minMicros(41_000_000));
    }
}
