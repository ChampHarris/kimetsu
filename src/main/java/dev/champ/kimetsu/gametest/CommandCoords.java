package dev.champ.kimetsu.gametest;

import java.math.BigDecimal;

/** Coordinates written into a command a test runs, so the command lands exactly where the test meant. */
final class CommandCoords {
    private CommandCoords() {}

    /**
     * {@code v} as a command's coordinate parser reads it back exactly. Plain decimals: a test can sit 14 million blocks
     * out, where {@code Double.toString} writes an exponent the parser stops at. Always with a point: the parser centres
     * a whole number on its block (x and z + 0.5, {@code WorldCoordinate.parseDouble}), and from 10 million blocks
     * {@code toPlainString} writes a whole one without (D-074: a test placed there landed half a block off).
     */
    static String plain(double v) {
        BigDecimal d = BigDecimal.valueOf(v);
        return (d.scale() > 0 ? d : d.setScale(1)).toPlainString();
    }
}
