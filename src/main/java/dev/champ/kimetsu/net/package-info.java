/**
 * The wire (spec Netcode, "Payloads"): one versioned {@code kimetsu:net} channel whose messages each start with a
 * type byte, the codecs for the engine's events and effects, and the clock's network-thread work at the server's end.
 */
@NullMarked
package dev.champ.kimetsu.net;

import org.jspecify.annotations.NullMarked;
