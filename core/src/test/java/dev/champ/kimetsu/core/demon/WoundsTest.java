package dev.champ.kimetsu.core.demon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.HungerRules;
import dev.champ.kimetsu.core.data.PostureRules;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.meter.Fixed;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A demon's wounds as a save holds them (slice 5c-2, D-063): a body restored from the wounds another took in the tick it
 * was running goes on exactly as that one would have, tick for tick, on ticks renumbered by any amount, through a
 * stagger, a collapse and its rise, the Threads a break and a refilled Hunger open, a regeneration pause and vitality.
 */
class WoundsTest {
    private static final long SHIFT = 7_003; // the restored body's ticks run this far ahead of the old one's
    private static final int RUN = 300;

    /** The M1 demon with a 40-tick regeneration pause after a Nichirin wound, so the pause has something to carry. */
    private static DemonProfile profile(int hp, PostureRules posture, HungerRules hunger) {
        DemonProfile d = Fixtures.demon();
        return new DemonProfile(
                d.id(),
                d.body(),
                hp,
                posture,
                hunger,
                new DemonProfile.Regen(d.regen().hpPerS(), 40, DemonProfile.PauseOn.NICHIRIN),
                d.neck(),
                d.clang(),
                d.threadTicks(),
                d.collapse(),
                d.outsideFloor(),
                d.knockdown(),
                d.knockbackTicks(),
                d.guard(),
                d.strings(),
                d.senses(),
                d.escapeRecoveryTicks(),
                d.clash());
    }

    private static final DemonProfile M1 = profile(100, Fixtures.demonPosture(), Fixtures.hunger());

    /** Something that happens to a body at a tick. */
    private interface Act {
        void on(DemonBody b, long t);
    }

    private record At(long tick, Act act) {}

    /**
     * Runs {@code script} on a body to tick {@code now}, takes its wounds in the tick being run (its events in, its own
     * processes not yet), restores them into a body on ticks {@link #SHIFT} later, then runs both {@link #RUN} ticks
     * more, the script's later events at the same moments, and checks they agree every tick.
     */
    private static void savedAt(long now, List<At> script) {
        savedAt(now, script, (b, t) -> {});
    }

    /** The same, {@code leaving} done to the old body once its wounds are taken: what leaving the fight does to it. */
    private static void savedAt(long now, List<At> script, Act leaving) {
        DemonBody old = new DemonBody(M1, Fixtures.hits(), -1);
        for (long u = 0; u < now; u++) {
            play(script, old, u, u);
            old.advance(u);
        }
        play(script, old, now, now);
        Demon.Wounds w = old.wounds(now);
        leaving.on(old, now);
        DemonBody back = new DemonBody(M1, Fixtures.hits(), now + SHIFT - 1);
        back.restore(w, now + SHIFT);
        for (long u = now; u < now + RUN; u++) {
            if (u > now) {
                play(script, old, u, u);
                play(script, back, u, u + SHIFT);
            }
            old.advance(u);
            back.advance(u + SHIFT);
            String at = "saved at " + now + ", tick " + u;
            assertEquals(old.hp, back.hp, at + ": HP");
            assertEquals(old.hunger.value(), back.hunger.value(), at + ": Hunger");
            assertEquals(old.posture.at(u), back.posture.at(u + SHIFT), at + ": Posture");
            assertEquals(old.state(u), back.state(u + SHIFT), at + ": state");
            assertEquals(old.regenerating(u), back.regenerating(u + SHIFT), at + ": regenerating");
            assertEquals(shifted(old.threadUntil(u)), back.threadUntil(u + SHIFT), at + ": the Thread");
        }
    }

    private static void play(List<At> script, DemonBody b, long u, long t) {
        for (At a : script) {
            if (a.tick() == u) {
                a.act().on(b, t);
            }
        }
    }

    private static long shifted(long until) {
        return until == DemonBody.NONE || until == Long.MAX_VALUE ? until : until + SHIFT;
    }

    @Test
    void aBrokenPosture_itsStagger_itsThread_andARegenerationPause_runOnFromTheSave() {
        // a cut at 10 for 30 HP that breaks Posture: staggered to 40, the Thread open to 40, regeneration paused to 50,
        // Posture recovering from 70 at a vitality of 0.775
        List<At> script = List.of(new At(10, (b, t) -> b.hitBody(t, Fixed.of(30), Fixed.of(400), 0, false, false)));
        for (long now : new long[] {0, 10, 11, 25, 39, 40, 41, 49, 50, 51, 69, 70, 71, 200}) {
            savedAt(now, script);
        }
    }

    @Test
    void aCollapse_itsThread_andItsRise_runOnFromTheSave() {
        // a lethal cut at 5: lying at 0.5 HP through 105, rising at its end at a quarter, paying 25 Hunger
        List<At> script = List.of(new At(5, (b, t) -> b.hitBody(t, Fixed.of(200), 0, 0, false, false)));
        for (long now : new long[] {5, 6, 50, 104, 105, 106, 120}) {
            savedAt(now, script);
        }
    }

    @Test
    void emptyHungerAndTheThreadItsRefillOpens_runOnFromTheSave() {
        // Hunger emptied at 10 (the Thread open while it's 0), refilled at 20 (open 30 ticks more, to 50)
        List<At> script = List.of(
                new At(10, (b, t) -> b.hunger.pay(b.hunger.value())),
                new At(20, (b, t) -> b.hunger.carry(Fixed.of(50))));
        for (long now : new long[] {10, 15, 20, 21, 35, 49, 50, 51}) {
            savedAt(now, script);
        }
    }

    @Test
    void aStaggerAndACollapseTogether_andHitsAfterTheSave_agree() {
        List<At> script = List.of(
                new At(10, (b, t) -> b.hitBody(t, Fixed.of(60), Fixed.of(400), 0, false, false)),
                new At(30, (b, t) -> b.hitBody(t, Fixed.of(60), 0, 0, false, false)),
                new At(150, (b, t) -> b.hitBody(t, Fixed.of(10), Fixed.of(100), 0, false, false)));
        for (long now : new long[] {10, 20, 30, 31, 100, 140}) {
            savedAt(now, script);
        }
    }

    @Test
    void thePostureItsGuardStaked_comesBackRefunded_asADroppedCounterRefundsIt() {
        // a cut at 5 takes 100 Posture (recovery held to 65), then two staked blocks of 8 at 10 and 12: 184 shown, 16
        // staked toward a guard counter asked for and never committed; leaving the fight drops it, refunding the 16
        List<At> script = List.of(
                new At(5, (b, t) -> b.hitBody(t, 0, Fixed.of(100), 0, false, false)),
                new At(10, (b, t) -> b.block(t, Fixed.of(8), true, true, 0, 1)),
                new At(12, (b, t) -> b.block(t, Fixed.of(8), true, true, 0, 1)));
        for (long now : new long[] {10, 12, 13, 40}) {
            savedAt(now, script, (b, t) -> b.counterRefused(t));
        }
        DemonBody b = new DemonBody(M1, Fixtures.hits(), -1);
        for (long u = 0; u < 13; u++) {
            play(script, b, u, u);
            b.advance(u);
        }
        assertEquals(Fixed.of(184), b.posture.at(13));
        assertEquals(Fixed.of(200), b.wounds(13).posture(), "the stake comes back");
    }

    @Test
    void timersLongerThanTheProfileRunsThem_areCut_andNoneOverflows() {
        long huge = Long.MAX_VALUE;
        Demon.Wounds w =
                new Demon.Wounds(Fixed.of(50), Fixed.of(80), Fixed.of(100), 0, huge, huge, huge, 0, 0, huge, false);
        DemonBody b = new DemonBody(M1, Fixtures.hits(), 99);
        b.restore(w, 100);
        assertTrue(b.staggered(129));
        assertFalse(b.staggered(130), "a stagger runs the profile's 30 ticks at most");
        assertEquals(101, b.posture.recoveryDelay(100), "recovery waits no longer than a collapse holds it");
        assertFalse(b.regenerating(139));
        assertTrue(b.regenerating(140), "the regeneration pause runs the profile's 40 ticks at most");
        assertEquals(130, b.threadUntil(100), "a refill's Thread runs the profile's 30 ticks at most");
    }

    @Test
    void anUnhurtDemon_hasNoWounds() {
        DemonBody b = new DemonBody(M1, Fixtures.hits(), -1);
        Demon.Wounds w = b.wounds(0);
        assertEquals(new Demon.Wounds(Fixed.of(100), Fixed.of(100), Fixed.of(300), 0, 0, 0, 0, 0, 0, 0, false), w);
    }

    @Test
    void newDataHoldsTheWoundsToItsMaxima_andNeverHeals() {
        Demon.Wounds w =
                new Demon.Wounds(Fixed.of(80), Fixed.of(90), Fixed.of(250), Fixed.of(20), 0, 0, 0, 0, 0, 0, false);
        DemonProfile small =
                profile(50, new PostureRules(200, 12, 60, 0, 30, 0.5, 0.25), new HungerRules(60, 1, 5, 25, 0.25));
        DemonBody b = new DemonBody(small, Fixtures.hits(), 99);
        b.restore(w, 100);
        assertEquals(Fixed.of(50), b.hp);
        assertEquals(Fixed.of(60), b.hunger.value());
        assertEquals(Fixed.of(200), b.posture.at(100));
        DemonBody big = new DemonBody(profile(500, Fixtures.demonPosture(), Fixtures.hunger()), Fixtures.hits(), 99);
        big.restore(w, 100);
        assertEquals(Fixed.of(80), big.hp, "a bigger max HP heals nothing");
    }

    @Test
    void aCollapseOrAThreadLongerThanTheProfileAllows_isCut_soNeitherStartsLate() {
        Demon.Wounds w = new Demon.Wounds(Fixed.of(1), Fixed.of(50), 0, Fixed.of(99), 0, 0, 0, 900, 900, 0, false);
        DemonBody b = new DemonBody(M1, Fixtures.hits(), 99);
        b.restore(w, 100);
        assertEquals(State.COLLAPSED, b.state(100));
        assertTrue(b.collapsed(200));
        assertFalse(b.collapsed(201), "it lies the profile's 100 ticks at most");
        assertEquals(201, b.threadUntil(200));
    }

    @Test
    void woundsAreNeverNegative() {
        assertThrows(IllegalArgumentException.class, () -> new Demon.Wounds(-1, 0, 0, 0, 0, 0, 0, 0, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new Demon.Wounds(0, 0, 0, 0, 0, 0, 0, -5, 0, 0, false));
    }
}
