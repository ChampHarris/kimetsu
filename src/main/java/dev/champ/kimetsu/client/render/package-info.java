/**
 * What the skeleton client draws (slice 5b): the stand-in combatants on vanilla's humanoid model, the cue marker by
 * the crosshair and the debug overlay. No raw OpenGL; everything through vanilla's renderers and GUI layers. It reads
 * only what the server routed to this player ({@code ClientCombat}), never the integrated server.
 */
@NullMarked
package dev.champ.kimetsu.client.render;

import org.jspecify.annotations.NullMarked;
