/**
 * The server's fight loop (the tick order's steps 2–7, Rule 3): players' state machines and pose histories, the mobs
 * that strike them, the strike book, and each strike's decision at its impact. The mob's mind, its body and the
 * level's blocks are plug-ins ({@link dev.champ.kimetsu.core.fight.Brain}, {@link dev.champ.kimetsu.core.fight.StrikeMob},
 * {@link dev.champ.kimetsu.core.hit.Sight}): slice 4's demon and slice 5's mod fill them in; tests use a scripted
 * striker, a box dummy and open air.
 */
@NullMarked
package dev.champ.kimetsu.core.fight;

import org.jspecify.annotations.NullMarked;
