package dev.champ.kimetsu.client.present;

/** What a combatant's stand-in shows at an instant: a stance, or a strike. Mutable and reused ({@link Poser}'s). */
public final class Layer {
    public enum Kind {
        STANCE,
        STRIKE
    }

    private Kind kind = Kind.STANCE;
    private Stance stance = Stance.NEUTRAL;
    final StrikeSpan strike = new StrikeSpan();

    public Kind kind() {
        return kind;
    }

    /** Its stance; neutral for a strike. */
    public Stance stance() {
        return stance;
    }

    void stance(Stance s) {
        kind = Kind.STANCE;
        stance = s;
    }

    void striking() {
        kind = Kind.STRIKE;
        stance = Stance.NEUTRAL;
    }

    boolean same(Layer o) {
        return kind == o.kind && (kind == Kind.STRIKE ? strike.id() == o.strike.id() : stance == o.stance);
    }

    void copy(Layer o) {
        kind = o.kind;
        stance = o.stance;
        strike.copy(o.strike);
    }
}
