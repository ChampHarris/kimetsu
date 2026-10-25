package dev.champ.kimetsu.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.champ.kimetsu.core.clock.TickRecord;
import org.junit.jupiter.api.Test;

/**
 * The epoch the network thread hands the frame gate as announced (D-065; the 5c-1 review's L2): the server's epoch
 * moves before {@code ClockEpoch} goes out and before the record carrying it is published, so a frame in between is
 * flushed against the server's epoch, never the stale record's.
 */
class ServerNetTest {
    private static TickRecord record(int epoch) {
        return new TickRecord(1234, 9.87654321e11, 5.0e7, false, false, false, epoch);
    }

    @Test
    void theAnnouncedEpochIsTheLaterOfTheRecordsAndTheServers() {
        assertEquals(4, ServerNet.announced(record(3), 4), "the server moved first: its record still says 3");
        assertEquals(4, ServerNet.announced(record(4), 4));
        assertEquals(5, ServerNet.announced(record(5), 4), "never earlier than the record");
    }
}
