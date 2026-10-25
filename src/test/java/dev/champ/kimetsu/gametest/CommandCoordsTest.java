package dev.champ.kimetsu.gametest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A test's {@code /tp} coordinates through vanilla's own parser, wherever the GameTest server puts the test: it picks
 * the grid's x and z at random in ±14,999,992, so a GameTest met the 10-million edge only in some runs (D-074).
 */
class CommandCoordsTest {
    /** Whole and half blocks either side of 10 million, out past the grid's edge, zero both ways, tiny, huge. */
    private static final double[] SENT = {
        0.0,
        -0.0,
        4.0,
        -4.5,
        9_999_999.0,
        10_000_000.0,
        11_851_019.0,
        -14_121_058.0,
        14_174_878.5,
        15_000_022.0,
        1e8,
        1e-5,
        -3.2e-7
    };

    @Test
    void theParserReadsEveryCoordinateBackExactly() throws CommandSyntaxException {
        for (double v : SENT) {
            String s = CommandCoords.plain(v);
            Vec3 read = parse(s + " " + s + " " + s);
            assertTrue(read.x == v && read.y == v && read.z == v, s + " read back as " + read);
        }
    }

    /** The trap {@code plain} avoids: what {@code toPlainString} alone wrote for D-074's far tests. */
    @Test
    void aWholeNumberWithNoPointIsCentredOnItsBlock() throws CommandSyntaxException {
        assertEquals(new Vec3(11_851_019.5, 4, 11_851_019.5), parse("11851019 4 11851019"));
    }

    /** As {@code /tp}'s location argument reads it: x and z centred when whole, y as written. */
    private static Vec3 parse(String xyz) throws CommandSyntaxException {
        WorldCoordinates c = (WorldCoordinates) Vec3Argument.vec3().parse(new StringReader(xyz));
        return new Vec3(c.x().get(0), c.y().get(0), c.z().get(0));
    }
}
