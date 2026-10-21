/**
 * The M1 demon (Rules 8, 10.4, 11): its body ({@link dev.champ.kimetsu.core.demon.Demon}, a {@code StrikeMob} with its
 * own state machine, meters, neck, guard stance and guard counter's stake, kept per tick so late events are judged as
 * of their tick) and, from slice 4b, its brain. Plain Java: the mod's entity, its movement and its model adapt it.
 */
@NullMarked
package dev.champ.kimetsu.core.demon;

import org.jspecify.annotations.NullMarked;
