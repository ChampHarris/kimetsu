package dev.champ.kimetsu.client.present;

/**
 * Where a combatant's body is drawn this frame (D-145's A5): one frame shared by the stand-in renderer, the world cues
 * and 5d-4b's trails, so a glint never floats off the body it marks. From its entity's own interpolated pose
 * ({@link #entity}), moved {@code weight} of the way onto its strike path's place, the head turning with the body
 * ({@link #place}, {@link Poser#place}), then turned toward its guard's yaw, the head keeping its look ({@link #guard},
 * Rule 11.4). Degrees are Minecraft's, the head's relative to the body as vanilla's render state keeps it; the turns are
 * vanilla's own {@code Mth.rotLerp} and {@code wrapDegrees}, to the bit, so the stand-in draws as it did before the
 * cues shared its frame. Mutable and reused; allocates nothing.
 */
public final class DrawnFrame {
    /** The feet. */
    public double x;

    public double y;
    public double z;

    /** The body's yaw (vanilla's {@code bodyRot}). */
    public float bodyYaw;

    /** The head's yaw relative to the body (vanilla's {@code yRot}). */
    public float headYaw;

    /** The entity's own pose: its feet, its body's yaw and its head's, relative to the body. */
    public DrawnFrame entity(double x, double y, double z, float bodyYaw, float headYaw) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.bodyYaw = bodyYaw;
        this.headYaw = headYaw;
        return this;
    }

    /** Moves the body {@code weight} of the way onto its strike path's place facing {@code yaw}, the head with it. */
    public DrawnFrame place(float weight, double px, double py, double pz, float yaw) {
        if (weight > 0) {
            float head = bodyYaw + headYaw;
            x += (px - x) * weight;
            y += (py - y) * weight;
            z += (pz - z) * weight;
            bodyYaw = rotLerp(weight, bodyYaw, yaw);
            headYaw = wrapDegrees(rotLerp(weight, head, yaw) - bodyYaw);
        }
        return this;
    }

    /** Turns the body {@code weight} of the way to its guard's {@code yaw}, the head keeping its look. */
    public DrawnFrame guard(float weight, float yaw) {
        if (weight > 0) {
            float head = bodyYaw + headYaw;
            bodyYaw = rotLerp(weight, bodyYaw, yaw);
            headYaw = wrapDegrees(head - bodyYaw);
        }
        return this;
    }

    /** Vanilla's {@code Mth.rotLerp}: {@code a} of the short way from {@code from} to {@code to}. */
    private static float rotLerp(float a, float from, float to) {
        return from + a * wrapDegrees(to - from);
    }

    /** Vanilla's {@code Mth.wrapDegrees}: into [−180, 180). */
    private static float wrapDegrees(float angle) {
        float w = angle % 360.0F;
        if (w >= 180.0F) {
            w -= 360.0F;
        }
        if (w < -180.0F) {
            w += 360.0F;
        }
        return w;
    }
}
