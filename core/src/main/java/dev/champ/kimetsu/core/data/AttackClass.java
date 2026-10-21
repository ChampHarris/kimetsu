package dev.champ.kimetsu.core.data;

/**
 * What an attack is (frame data's {@code class}). It decides which hits an attack's armour absorbs (Rule 7.6) and which
 * attacks can clash (heavy against heavy, Rule 10.1). A Step is frame data too, with no hit windows.
 */
public enum AttackClass {
    LIGHT,
    HEAVY,
    GRAB,
    POMMEL,
    STEP
}
