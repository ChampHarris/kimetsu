package dev.champ.kimetsu.combat;

import static dev.champ.kimetsu.net.RecordSamples.records;
import static dev.champ.kimetsu.net.RecordSamples.sample;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.fight.Fights;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.core.machine.Effect;
import java.io.StringWriter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The fight log's lines (D-069): every engine event and effect, found by walking their sealed types as the wire's test
 * does, is a line of valid JSON with its type, its step, and each of its record's fields under its own name with its
 * own value, and nothing else; strings are escaped; the log's own lines parse too, after its header.
 */
class FightLogJsonTest {
    private final FightLogJson json = new FightLogJson();

    private static JsonObject parse(CharSequence line) {
        return JsonParser.parseString(line.toString()).getAsJsonObject();
    }

    /** {@code o} holds record {@code r}'s fields under their names, with their values, and {@code extra} keys more. */
    private static void holds(JsonObject o, Record r, int extra) throws ReflectiveOperationException {
        RecordComponent[] parts = r.getClass().getRecordComponents();
        String what = r.getClass().getSimpleName();
        assertEquals(parts.length + extra, o.size(), what + "'s keys: " + o.keySet());
        for (RecordComponent c : parts) {
            Object v = c.getAccessor().invoke(r);
            JsonElement j = o.get(c.getName());
            assertNotNull(j, what + " has no " + c.getName());
            switch (v) {
                case Long l -> assertEquals(l.longValue(), j.getAsLong(), what + "." + c.getName());
                case Integer i -> assertEquals(i.intValue(), j.getAsInt(), what + "." + c.getName());
                case Double d -> assertEquals(d, j.getAsDouble(), what + "." + c.getName());
                case Boolean b -> assertEquals(b, j.getAsBoolean(), what + "." + c.getName());
                case String s -> assertEquals(s, j.getAsString(), what + "." + c.getName());
                case Enum<?> e -> assertEquals(e.name(), j.getAsString(), what + "." + c.getName());
                case Effect e -> {
                    JsonObject nested = j.getAsJsonObject();
                    assertEquals(e.getClass().getSimpleName(), nested.get("t").getAsString());
                    holds(nested, (Record) e, 1);
                }
                default -> throw new AssertionError(what + "." + c.getName() + " is a " + v.getClass());
            }
        }
    }

    @Test
    void everyEventIsALineWithItsTypeStepAndEveryField() throws ReflectiveOperationException {
        List<Class<?>> types = records(Outbox.Event.class);
        assertEquals(22, types.size(), "the outbox's events"); // GuardJudged joined (5c-4a)
        for (int seed = 0; seed < 3; seed++) {
            for (Class<?> t : types) {
                Outbox.Event e = (Outbox.Event) sample(t, seed);
                JsonObject o = parse(json.event(e, 1234 + seed));
                assertEquals(t.getSimpleName(), o.get("t").getAsString());
                assertEquals(1234 + seed, o.get("n").getAsLong());
                holds(o, (Record) e, 2);
            }
        }
    }

    @Test
    void everyEffectNestsInItsPlayerEffectWithEveryField() throws ReflectiveOperationException {
        List<Class<?>> types = records(Effect.class);
        assertEquals(20, types.size(), "the engine's effects"); // Push joined (5c-3b)
        for (int seed = 0; seed < 3; seed++) {
            for (Class<?> t : types) {
                Outbox.PlayerEffect p = new Outbox.PlayerEffect(7, 99, (Effect) sample(t, seed));
                holds(parse(json.event(p, 100)), p, 2);
            }
        }
    }

    @Test
    void stringsAreEscapedAndNonFiniteNumbersAreNull() {
        String why = "a \"quoted\" \\ why\n\twith \u0001 in it";
        JsonObject o = parse(json.event(new Outbox.AttackCancelled(1, 2, why), 3));
        assertEquals(why, o.get("why").getAsString());
        JsonObject locked = parse(json.event(new Outbox.StrikeLocked(1, Double.NaN, 0, 0, 0, 0.5), 3));
        assertTrue(locked.get("yaw").isJsonNull(), "JSON has no NaN: " + locked);
    }

    @Test
    void theLogsOwnLinesParse_afterItsHeader() {
        StringWriter into = new StringWriter();
        FightLog log = FightLog.toMemory("minecraft:overworld", into);
        Fights fights = new Fights(Fixtures.fights(), log);
        fights.accept(new Outbox.AttackStarted(1, 10, 1, "swipe", 87, 90, 92, 100, 1, true), 87);
        fights.defeated(10, 120);
        log.dropped(121, 1, PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, 6_000_000), PlayerIntake.Drop.OLD_EPOCH);
        log.refused(122, 1, 10, 2.5);
        log.rebuilt(123, 4, -40);
        log.damage(124, 1, "kimetsu:demon_claw", 3, 3, 3, 0, 17);
        log.close();
        List<String> lines = into.toString().lines().toList();
        assertEquals("open", parse(lines.get(0)).get("t").getAsString());
        assertEquals("minecraft:overworld", parse(lines.get(0)).get("arena").getAsString());
        List<String> kinds = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            kinds.add(parse(lines.get(i)).get("t").getAsString());
        }
        assertEquals(
                List.of(
                        "fight_start",
                        "fight_start",
                        "fight_end",
                        "fight_end",
                        "dropped",
                        "refused",
                        "rebuild",
                        "damage"),
                kinds);
        JsonObject end = parse(lines.get(4));
        assertEquals(1, end.get("id").getAsInt());
        assertEquals("FOES_DEFEATED", end.get("why").getAsString());
        assertEquals(87, end.get("from").getAsLong());
        assertEquals(10, end.get("foes").getAsJsonArray().get(0).getAsInt());
        assertTrue(end.get("down").getAsJsonArray().get(0).getAsBoolean());
        assertEquals("OLD_EPOCH", parse(lines.get(5)).get("why").getAsString());
        assertEquals(-40, parse(lines.get(7)).get("shift").getAsLong());
        assertTrue(log.off(), "closed");
    }
}
