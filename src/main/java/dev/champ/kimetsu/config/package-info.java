/**
 * The mod's two configs (spec Config, D-104): SYNCED, per server and sent to its clients, and CLIENT, the player's own.
 * Each copies its values into one immutable record on every load and reload, which code reads instead of the spec.
 */
@NullMarked
package dev.champ.kimetsu.config;

import org.jspecify.annotations.NullMarked;
