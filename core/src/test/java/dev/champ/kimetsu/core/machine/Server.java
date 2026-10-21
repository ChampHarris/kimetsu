package dev.champ.kimetsu.core.machine;

import java.util.function.Consumer;

/**
 * One player's {@link Timeline} on a pretend server: {@link #deliver} puts a press on the wire, and it arrives in the
 * next tick begun; {@link #tick} runs one tick with the world's work (sweeps, strikes, effects) between its two halves.
 */
final class Server {
    final Rig rig;
    final Timeline t;

    Server() {
        this(new Rig(), c -> {});
    }

    /** {@code setup} prepares the combatant before tick 1. */
    Server(Consumer<Combatant> setup) {
        this(new Rig(), setup);
    }

    /** A server on {@code rig}'s moveset and Pulse, which another one can share (a client and its server). */
    Server(Rig rig, Consumer<Combatant> setup) {
        this.rig = rig;
        Combatant c = rig.fresh();
        setup.accept(c);
        t = new Timeline(c, 1);
    }

    Server deliver(Input in) {
        if (!t.receive(in)) {
            throw new IllegalStateException("dropped " + in);
        }
        return this;
    }

    Server deliver(Input.Kind kind, long micros) {
        return deliver(Input.of(kind, micros));
    }

    /** Runs the next tick, with {@code world} between {@code beginTick} and {@code endTick}. */
    Server tick(Consumer<Timeline> world) {
        long n = t.current() + 1;
        t.beginTick(n);
        world.accept(t);
        t.endTick(n);
        return this;
    }

    Server through(long tick) {
        while (t.current() < tick) {
            tick(x -> {});
        }
        return this;
    }

    Combatant live() {
        return t.live();
    }
}
