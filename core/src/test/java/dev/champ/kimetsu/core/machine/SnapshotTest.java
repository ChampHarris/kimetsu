package dev.champ.kimetsu.core.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.meter.Segment;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The guarantees replay rests on (the plan's risks 2 and 3): the machine is deterministic, a snapshot holds everything
 * it mutates, and {@code sameAs} compares all of it. Random play drives it through most of its states; reflection finds
 * every field, so a field added later without a copy or a comparison fails here.
 */
class SnapshotTest {
    private static final int TICKS = 400;
    private static final Set<String> NOT_STATE = Set.of("version"); // the meters' sync counters, moved by any copy

    /** One tick's script: presses in stamp order, then effects. */
    private record Tick(List<Input> presses, List<Effect> effects) {}

    private static List<Tick> randomPlay(long seed) {
        Random rnd = new Random(seed);
        List<Tick> script = new ArrayList<>();
        script.add(new Tick(List.of(), List.of()));
        for (int n = 1; n <= TICKS; n++) {
            List<Input> presses = new ArrayList<>();
            int count = rnd.nextInt(10) < 3 ? 1 + rnd.nextInt(2) : 0;
            List<Long> stamps = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                stamps.add(Micros.ofTicks(n - 1) + 1 + rnd.nextInt(50_000)); // acts in step n
            }
            stamps.sort(null);
            for (long stamp : stamps) {
                presses.add(randomPress(rnd, stamp));
            }
            List<Effect> effects = new ArrayList<>();
            if (rnd.nextInt(10) == 0) {
                effects.add(randomEffect(rnd, n));
            }
            script.add(new Tick(presses, effects));
        }
        return script;
    }

    @SuppressWarnings("EnumOrdinal") // a random pick among the kinds
    static Input randomPress(Random rnd, long stamp) {
        Input.Kind kind = Input.Kind.values()[rnd.nextInt(Input.Kind.values().length)];
        return switch (kind) {
            case ATTACK -> rnd.nextInt(6) == 0 ? Input.attackFalling(stamp, 2.5) : Input.of(kind, stamp);
            case GUARD_DOWN -> Input.guard(stamp);
            case BREATHE_UP -> Input.release(stamp, rnd.nextInt(5) - 1);
            default -> Input.of(kind, stamp);
        };
    }

    static Effect randomEffect(Random rnd, long n) {
        return switch (rnd.nextInt(19)) {
            case 0 -> new Effect.Hit(rnd.nextBoolean() ? AttackClass.LIGHT : AttackClass.HEAVY, 1 + rnd.nextInt(20));
            case 1 -> new Effect.Blocked();
            case 2 -> new Effect.PostureDamage(Rig.points(5 + rnd.nextInt(120)), rnd.nextBoolean());
            case 3 -> new Effect.Knockdown();
            case 4, 5 ->
                new Effect.Contact(
                        rnd.nextBoolean() ? Effect.Surface.FLOOR : Effect.Surface.WALL,
                        Micros.ofTicks(n) - rnd.nextInt(60_000));
            case 6 -> new Effect.Grabbed(15);
            case 7 -> new Effect.Released();
            case 8 -> new Effect.ClashStart();
            case 9 -> new Effect.ClashEnd();
            case 10 -> new Effect.Credit(n / Rig.BEAT_TICKS);
            case 11 -> new Effect.RhythmDelta(rnd.nextInt(9) - 2);
            case 12 -> new Effect.Connected();
            case 13 -> new Effect.InCombat(rnd.nextBoolean());
            case 14 -> new Effect.AssistChanged(rnd.nextInt(4));
            case 15 ->
                new Effect.StrikeCommitted(
                        rnd.nextInt(4), Micros.ofTicks(n + 8) + rnd.nextInt(200_000), rnd.nextInt(4) > 0);
            case 16 -> new Effect.StrikeCancelled(rnd.nextInt(4));
            case 17 -> new Effect.StrikeDecided(rnd.nextInt(4), rnd.nextBoolean());
            default -> rnd.nextInt(4) == 0 ? new Effect.Collapse(20 + rnd.nextInt(40)) : Effect.TARGETED;
        };
    }

    private static void run(Combatant c, long n, Tick t) {
        for (Input in : t.presses()) {
            c.input(in, n, n);
        }
        c.advance(n);
        for (Effect e : t.effects()) {
            c.effect(e, n);
        }
        c.meters(n);
    }

    /** The straight run's state at the end of every tick. */
    private static List<Combatant> straight(Rig rig, List<Tick> script) {
        Combatant c = rig.fresh();
        List<Combatant> ends = new ArrayList<>();
        Combatant start = rig.fresh();
        start.copyFrom(c);
        ends.add(start);
        for (int n = 1; n <= TICKS; n++) {
            run(c, n, script.get(n));
            Combatant snap = rig.fresh();
            snap.copyFrom(c);
            ends.add(snap);
        }
        return ends;
    }

    @Test
    void replayingFromAnySnapshotReachesTheStraightRunsStateAtEveryTick() {
        for (long seed = 1; seed <= 12; seed++) {
            Rig rig = new Rig();
            List<Tick> script = randomPlay(seed);
            List<Combatant> ends = straight(rig, script);
            for (int k = 0; k < TICKS; k += 23) {
                Combatant m = rig.fresh();
                m.copyFrom(ends.get(k));
                for (int n = k + 1; n <= TICKS; n++) {
                    run(m, n, script.get(n));
                    assertTrue(m.sameAs(ends.get(n)), "seed " + seed + ", from " + k + ", at " + n);
                }
            }
        }
    }

    @Test
    void randomPlayReachesMostOfTheMachine() {
        Set<State> seen = EnumSet.noneOf(State.class);
        for (long seed = 1; seed <= 12; seed++) {
            Rig rig = new Rig();
            for (Combatant c : straight(rig, randomPlay(seed))) {
                seen.add(c.state());
            }
        }
        seen.add(State.DEAD); // random play never kills: it would end the run
        assertEquals(Set.of(State.values()), seen);
    }

    @Test
    void copyFromCopiesEveryField() throws IllegalAccessException {
        Rig rig = new Rig();
        List<Combatant> ends = straight(rig, randomPlay(7));
        List<Field[]> paths = statePaths();
        int differed = 0;
        for (int k = 5; k + 61 <= TICKS; k += 17) {
            Combatant target = rig.fresh();
            target.copyFrom(ends.get(k + 61)); // another state, so a field left uncopied shows
            for (Field[] path : paths) {
                if (!Objects.deepEquals(read(target, path), read(ends.get(k), path))) {
                    differed++;
                }
            }
            target.copyFrom(ends.get(k));
            for (Field[] path : paths) {
                assertTrue(
                        Objects.deepEquals(read(target, path), read(ends.get(k), path)),
                        name(path) + " after copying tick " + k);
            }
            assertTrue(target.sameAs(ends.get(k)));
        }
        assertTrue(differed > paths.size(), "the states compared were too alike to test the copy");
    }

    @Test
    void sameAsComparesEveryField() throws IllegalAccessException {
        Rig rig = new Rig();
        List<Combatant> ends = straight(rig, randomPlay(3));
        Combatant base = ends.get(TICKS / 2);
        for (Field[] path : statePaths()) {
            Field last = path[path.length - 1];
            if (last.getType().isArray()) {
                continue; // compared within their live range: InputBufferTest and the jump tests cover them
            }
            Combatant m = rig.fresh();
            m.copyFrom(base);
            Object owner = owner(m, path);
            last.set(owner, perturbed(rig, last, last.get(owner)));
            assertFalse(m.sameAs(base), name(path) + " changed but sameAs didn't notice");
        }
    }

    // ------------------------------------------------------------------------------------------- reflection

    /** Every mutable field of a combatant and of the meters it owns, as a path of fields from the combatant. */
    private static List<Field[]> statePaths() {
        List<Field[]> paths = new ArrayList<>();
        for (Field f : Combatant.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            Class<?> type = f.getType();
            if (Modifier.isFinal(f.getModifiers()) && owned(type)) {
                for (Field g : type.getDeclaredFields()) {
                    if (!Modifier.isStatic(g.getModifiers()) && !NOT_STATE.contains(g.getName()) && state(g)) {
                        g.setAccessible(true);
                        paths.add(new Field[] {f, g});
                    }
                }
            } else if (state(f)) {
                paths.add(new Field[] {f});
            }
        }
        return paths;
    }

    private static boolean owned(Class<?> type) {
        return type.getPackageName().startsWith("dev.champ.kimetsu.core.meter")
                || type == dev.champ.kimetsu.core.judge.CreditLedger.class
                || type == InputBuffer.class;
    }

    /** Mutable, or a final array whose elements are. */
    private static boolean state(Field f) {
        return !Modifier.isFinal(f.getModifiers()) || f.getType().isArray();
    }

    private static Object owner(Combatant c, Field[] path) throws IllegalAccessException {
        Object o = c;
        for (int i = 0; i < path.length - 1; i++) {
            o = path[i].get(o);
        }
        return o;
    }

    private static Object read(Combatant c, Field[] path) throws IllegalAccessException {
        Object v = path[path.length - 1].get(owner(c, path));
        if (v instanceof long[] a) {
            return Arrays.copyOf(a, a.length);
        }
        return v;
    }

    private static String name(Field[] path) {
        StringBuilder b = new StringBuilder();
        for (Field f : path) {
            b.append(b.isEmpty() ? "" : ".").append(f.getName());
        }
        return b.toString();
    }

    private static Object perturbed(Rig rig, Field f, Object v) {
        Class<?> t = f.getType();
        if (t == long.class) {
            long x = (Long) v;
            return x == Long.MIN_VALUE ? 0L : x + 1;
        }
        if (t == int.class) {
            return (Integer) v + 1;
        }
        if (t == boolean.class) {
            return !(Boolean) v;
        }
        if (t == double.class) {
            return (Double) v + 0.5;
        }
        if (t == State.class) {
            return v == State.NEUTRAL ? State.GUARD : State.NEUTRAL;
        }
        if (t == FrameData.class) {
            return Objects.equals(v, rig.moves.pommel()) ? rig.moves.step() : rig.moves.pommel();
        }
        if (t == Segment.class) {
            Segment s = (Segment) v;
            return new Segment(s.value() + 1, s.ratePerTick(), s.fromTick(), s.max());
        }
        throw new AssertionError("no perturbation for " + f);
    }
}
