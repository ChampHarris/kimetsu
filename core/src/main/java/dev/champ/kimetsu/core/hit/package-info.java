/**
 * What a swing touches (Rule 7): the reach shapes, three checks per active tick, line of sight, one hit per target per
 * hit window, and combo scaling. Plain doubles and reused objects, so a sweep allocates nothing (Performance Budget).
 * Coordinates are Minecraft's: y up, yaw 0 facing +z; an attacker's frame is x right, y up, z forward, from its feet.
 */
@NullMarked
package dev.champ.kimetsu.core.hit;

import org.jspecify.annotations.NullMarked;
