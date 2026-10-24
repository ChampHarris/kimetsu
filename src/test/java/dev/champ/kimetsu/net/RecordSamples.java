package dev.champ.kimetsu.net;

import dev.champ.kimetsu.core.machine.Effect;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * Samples of every record under a sealed type, for the tests that must cover each event and effect: the wire's codecs
 * and the fight log's lines. Each of a sample's fields gets its own value, so two fields swapped can't pass.
 */
public final class RecordSamples {
    private RecordSamples() {}

    /** The record classes under sealed type {@code root}, depth first. */
    public static List<Class<?>> records(Class<?> root) {
        List<Class<?>> out = new ArrayList<>();
        Class<?>[] permitted = root.getPermittedSubclasses();
        if (permitted == null) {
            if (root.isRecord()) {
                out.add(root);
            }
            return out;
        }
        for (Class<?> c : permitted) {
            out.addAll(records(c));
        }
        return out;
    }

    /** A sample of record {@code type}, its k-th field given a value that depends on k and on {@code seed}. */
    public static Object sample(Class<?> type, int seed) throws ReflectiveOperationException {
        RecordComponent[] parts = type.getRecordComponents();
        Class<?>[] types = new Class<?>[parts.length];
        Object[] values = new Object[parts.length];
        for (int k = 0; k < parts.length; k++) {
            Class<?> t = parts[k].getType();
            types[k] = t;
            int v = seed * 31 + k * 7 + 3;
            if (t == long.class) {
                values[k] = k % 3 == 2 ? -(1L << 40) - v : 1_000_000_000L * v + k; // large, small and negative
            } else if (t == int.class) {
                values[k] = k % 2 == 0 ? v : -v;
            } else if (t == double.class) {
                values[k] = v + 0.25;
            } else if (t == boolean.class) {
                values[k] = (v & 1) == 1;
            } else if (t == String.class) {
                values[k] = "kimetsu:sample/" + v;
            } else if (t.isEnum()) {
                Object[] constants = t.getEnumConstants();
                values[k] = constants[v % constants.length];
            } else if (t == Effect.class) {
                values[k] = new Effect.StrikeCommitted(v, 1_000_000L + v, true);
            } else {
                throw new IllegalStateException(type.getSimpleName() + " has a field of type " + t);
            }
        }
        Constructor<?> c = type.getDeclaredConstructor(types);
        return c.newInstance(values);
    }
}
