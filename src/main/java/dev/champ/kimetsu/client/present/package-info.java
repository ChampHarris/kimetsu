/**
 * Presentation time (slice 5d-3, D-123): the frame's one sample of the combat clock ({@code PresentClock}) and each
 * model's own time under holds ({@code ModelTime}), which every drawn thing reads instead of the raw clock, so a frame's
 * readers agree and the visual calibration applies once. Pure: no Minecraft types, so its rules are JUnit-tested.
 */
@NullMarked
package dev.champ.kimetsu.client.present;

import org.jspecify.annotations.NullMarked;
