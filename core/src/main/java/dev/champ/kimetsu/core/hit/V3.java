package dev.champ.kimetsu.core.hit;

/** A point or vector, reused so the sweeps allocate nothing. */
public final class V3 {
    double x;
    double y;
    double z;

    public V3 set(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }
}
