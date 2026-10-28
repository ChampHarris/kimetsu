package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.net.NetMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * When the client pings (spec Netcode, S524; a review nit: the schedule had no pure test): none before a record or while
 * paused; a burst of 4, one a client tick, after the first record, an epoch, an unfreeze or a sprint's start or end;
 * then one a second in combat and one every five seconds out of it; and after an unpause one every tick until a record
 * from after the pause comes. Client ticks here are 50 ms apart on the client's counter.
 */
class ClientClockTest {
    private static final int BEAT_TICKS = 13;

    private static long at(long clientTick) {
        return clientTick * 50_000_000L;
    }

    private static TickRecord record(long tick, boolean frozen, boolean sprinting, int epoch) {
        return new TickRecord(tick, tick * 50_000_000.0, 50_000_000.0, frozen, false, sprinting, epoch);
    }

    private static TickRecord record(long tick) {
        return record(tick, false, false, 0);
    }

    /** The client ticks in [{@code from}, {@code until}) a ping went on. */
    private static List<Long> pings(ClientClock c, long from, long until, boolean inCombat) {
        List<Long> out = new ArrayList<>();
        for (long t = from; t < until; t++) {
            if (c.pingDue(at(t), inCombat)) {
                out.add(t);
            }
        }
        return out;
    }

    @Test
    void noneBeforeARecord_thenABurstOfFour_thenOneASecondInCombat() {
        ClientClock c = new ClientClock(Fixtures.clock(), BEAT_TICKS, 0);
        assertEquals(List.of(), pings(c, 0, 20, true), "a ping before any record (the server answers none)");
        c.onRecord(record(500), at(20));
        assertEquals(List.of(20L, 21L, 22L, 23L, 43L, 63L), pings(c, 20, 70, true));
        assertEquals(1000, c.pingIntervalMillis());
    }

    @Test
    void everyFiveSecondsOutOfCombat_andAFightThatStartsShortensTheWaitAtOnce() {
        ClientClock c = new ClientClock(Fixtures.clock(), BEAT_TICKS, 0);
        c.onRecord(record(500), at(20));
        assertEquals(List.of(20L, 21L, 22L, 23L, 123L), pings(c, 20, 130, false));
        assertEquals(5000, c.pingIntervalMillis());
        assertEquals(List.of(143L, 163L), pings(c, 130, 170, true), "in combat from 130: a second after 123");
    }

    @Test
    void noneWhilePaused_andAfterTheUnpauseOneATickUntilARecordFromAfterThePauseComes() {
        ClientClock c = new ClientClock(Fixtures.clock(), BEAT_TICKS, 0);
        c.onRecord(record(500), at(20));
        assertEquals(List.of(20L, 21L, 22L, 23L), pings(c, 20, 30, true));
        c.paused(true, at(30));
        assertEquals(List.of(), pings(c, 30, 60, true), "a ping while paused");
        c.paused(false, at(60));
        assertEquals(List.of(60L, 61L, 62L, 63L, 64L), pings(c, 60, 65, true));
        c.onRecord(record(500), at(65)); // the pause's own tick: the estimate still stands still
        assertEquals(List.of(65L, 66L, 67L, 68L, 69L), pings(c, 65, 70, true));
        c.onRecord(record(501), at(70));
        assertEquals(List.of(89L), pings(c, 70, 100, true), "a record from after the pause ends the wait");
    }

    /** A pong received at client tick {@code t}, the server's wall clock the client's, carrying {@code r}. */
    private static void pong(ClientClock c, long t, TickRecord r) {
        c.onPong(new NetMessage.ClockPong(at(t) - 10_000_000L, at(t) - 5_000_000L, at(t) - 5_000_000L, r), at(t));
    }

    /**
     * D-121: the server runs on past the client's newest record (a pong a second) until it reads the pause, and its
     * network thread answers the unpause burst with that last tick's record. Newer than any the client had, it isn't
     * from after the pause, and neither is a pong held up across it: input stays shut and a ping goes every tick until
     * a tick that started after the pause comes, and the stamps then follow the server's line.
     */
    @Test
    void afterAnUnpauseOnlyATickThatStartedAfterThePauseOpensInput() {
        ClientClock c = new ClientClock(Fixtures.clock(), BEAT_TICKS, 0);
        c.onRecord(record(19), at(19)); // the session's first record; the server's tick t starts at the client's tick t
        assertEquals(List.of(19L, 20L, 21L, 22L), pings(c, 19, 23, true));
        for (long t = 20; t < 24; t++) {
            pong(c, t, record(t)); // the burst's answers
        }
        assertTrue(c.inputOpen());
        assertEquals(List.of(42L), pings(c, 23, 45, true));
        c.paused(true, at(45));
        pong(c, 46, record(42)); // the answer to tick 42's ping, held up until the game had paused
        c.paused(false, at(245)); // a 10 s pause
        assertFalse(c.inputOpen(), "open on a pong from before the pause");
        pong(c, 246, record(45)); // the server's last tick before it read the pause, answered before its first one back
        assertFalse(c.inputOpen(), "open on the record from before the pause");
        assertEquals(
                LongStream.range(246, 256).boxed().toList(), pings(c, 246, 256, true), "a ping a tick until it comes");
        double first = at(245) + 20_000_000.0; // the server's first tick back, re-anchored on vanilla's schedule
        c.onRecord(new TickRecord(46, first, 50_000_000.0, false, false, false, 0), at(256));
        assertTrue(c.inputOpen());
        assertEquals(Micros.ofTicks(46) + (at(266) - (long) first) / 1000, c.micros(at(266)), "off the server's line");
    }

    @Test
    void anEpochAnUnfreezeOrASprintsStartOrEndStartsABurst() {
        ClientClock c = new ClientClock(Fixtures.clock(), BEAT_TICKS, 0);
        c.onRecord(record(500), at(20));
        assertEquals(List.of(20L, 21L, 22L, 23L), pings(c, 20, 30, true));
        c.onEpoch(1);
        assertEquals(List.of(30L, 31L, 32L, 33L), pings(c, 30, 40, true), "after an epoch");
        c.onRecord(record(510, true, false, 1), at(40));
        assertEquals(List.of(), pings(c, 40, 45, true), "freezing starts no burst");
        c.onRecord(record(511, false, false, 1), at(45));
        assertEquals(List.of(45L, 46L, 47L, 48L), pings(c, 45, 50, true), "after an unfreeze");
        c.onRecord(record(520, false, true, 1), at(50));
        assertEquals(List.of(50L, 51L, 52L, 53L), pings(c, 50, 60, true), "after a sprint's start");
        c.onRecord(record(530, false, false, 1), at(60));
        assertEquals(List.of(60L, 61L, 62L, 63L, 83L), pings(c, 60, 90, true), "after a sprint's end");
    }
}
