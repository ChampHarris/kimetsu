/**
 * The client (spec Server/Client &amp; Netcode, Option B for M1's first build): the combat clock, the key capture and
 * input frames, and what the player sees, which follows the server's confirmations. Loaded on the physical client
 * only ({@code @Mod(dist = Dist.CLIENT)}); one {@code ClientCombat} per connection, so single player never shares a
 * client object with the integrated server.
 */
@NullMarked
package dev.champ.kimetsu.client;

import org.jspecify.annotations.NullMarked;
