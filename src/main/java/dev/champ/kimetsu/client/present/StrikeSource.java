package dev.champ.kimetsu.client.present;

/** One combatant's strikes, as its stand-in reads them ({@code ClientView}'s, bound to one attacker). */
@FunctionalInterface
public interface StrikeSource {
    /**
     * The newest of its strikes whose windup began at or before {@code micros}, ended or not, into {@code out}; false if
     * none.
     */
    boolean newest(long micros, StrikeSpan out);
}
