package dev.champ.kimetsu.client.kfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * The cue sheet's UV table (D-145's A3): each cell's UVs land half a texel inside it, so nearest sampling never reads
 * a neighbour; a strip's frames sit side by side and its frame count comes from the table; and the sheet on disk keeps
 * every cell's edge clear (a band's only across it), so the inset never cuts an outline; and it holds only the reserved
 * colours of {@code cue-reserved.gpl}, every coloured texel outlined within its cell (the art bible's §6).
 */
class CueSheetTest {
    private static final double EPS = 1e-7;

    @Test
    void eachCellsUvsSitHalfATexelInsideIt() {
        for (CueSheet c : CueSheet.values()) {
            for (int f = 0; f < c.frames(); f++) {
                assertEquals((c.x() + f * c.w() + 0.5) / CueSheet.WIDTH, c.u0(f), EPS, c + " u0 " + f);
                assertEquals((c.x() + (f + 1) * c.w() - 0.5) / CueSheet.WIDTH, c.u1(f), EPS, c + " u1 " + f);
            }
            assertEquals((c.y() + 0.5) / CueSheet.HEIGHT, c.v0(), EPS, c + " v0");
            assertEquals((c.y() + c.h() - 0.5) / CueSheet.HEIGHT, c.v1(), EPS, c + " v1");
        }
    }

    @Test
    void theStripsCarryTheirFrameCounts() {
        assertEquals(4, CueSheet.SHATTER.frames(), "the feint's shatter: 4 frames at 12 fps (D-144)");
        assertEquals(4, CueSheet.NECK.frames(), "the Thread's shimmer steps");
        assertEquals(1, CueSheet.GLINT.frames());
        assertEquals(1, CueSheet.CLASH.frames());
    }

    @Test
    void cellsStayOnTheSheetAndApart() {
        for (CueSheet a : CueSheet.values()) {
            assertTrue(a.x() >= 0 && a.x() + a.frames() * a.w() <= CueSheet.WIDTH, a + " runs off the sheet");
            assertTrue(a.y() >= 0 && a.y() + a.h() <= CueSheet.HEIGHT, a + " runs off the sheet");
            for (CueSheet b : CueSheet.values()) {
                if (a == b) {
                    continue;
                }
                boolean apart = a.x() + a.frames() * a.w() <= b.x()
                        || b.x() + b.frames() * b.w() <= a.x()
                        || a.y() + a.h() <= b.y()
                        || b.y() + b.h() <= a.y();
                assertTrue(apart, a + " overlaps " + b);
            }
        }
    }

    @Test
    void theSheetOnDiskKeepsEveryOutlineInsideItsCell() throws IOException {
        BufferedImage img = ImageIO.read(Path.of("src/main/resources/assets/kimetsu/textures/cue/cues.png")
                .toFile());
        assertEquals(CueSheet.WIDTH, img.getWidth());
        assertEquals(CueSheet.HEIGHT, img.getHeight());
        for (CueSheet c : CueSheet.values()) {
            for (int f = 0; f < c.frames(); f++) {
                int x0 = c.x() + f * c.w();
                int opaque = 0;
                for (int y = c.y(); y < c.y() + c.h(); y++) {
                    for (int x = x0; x < x0 + c.w(); x++) {
                        int alpha = img.getRGB(x, y) >>> 24;
                        boolean rowEdge = y == c.y() || y == c.y() + c.h() - 1;
                        boolean colEdge = x == x0 || x == x0 + c.w() - 1;
                        if (rowEdge || (colEdge && !c.band())) {
                            assertEquals(0, alpha, c + " frame " + f + " touches its edge at " + x + "," + y);
                        }
                        if (alpha == 255) {
                            opaque++;
                        }
                    }
                }
                assertTrue(opaque > 0, c + " frame " + f + " is empty");
            }
        }
    }

    @Test
    void theSheetUsesOnlyTheReservedColours_andEveryColouredTexelIsOutlinedInItsCell() throws IOException {
        Set<Integer> reserved = new HashSet<>();
        Pattern entry = Pattern.compile("^\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)");
        for (String line : Files.readAllLines(Path.of("art/palettes/cue-reserved.gpl"))) {
            Matcher m = entry.matcher(line);
            if (m.find()) {
                reserved.add(Integer.parseInt(m.group(1)) << 16
                        | Integer.parseInt(m.group(2)) << 8
                        | Integer.parseInt(m.group(3)));
            }
        }
        assertEquals(5, reserved.size(), "cue-reserved.gpl: white, red, purple, gold and the outline");
        int outline = 10 << 16 | 10 << 8 | 12;
        BufferedImage img = ImageIO.read(Path.of("src/main/resources/assets/kimetsu/textures/cue/cues.png")
                .toFile());
        for (CueSheet c : CueSheet.values()) {
            for (int f = 0; f < c.frames(); f++) {
                int x0 = c.x() + f * c.w();
                for (int y = c.y(); y < c.y() + c.h(); y++) {
                    for (int x = x0; x < x0 + c.w(); x++) {
                        int alpha = img.getRGB(x, y) >>> 24;
                        int rgb = img.getRGB(x, y) & 0xFFFFFF;
                        String at = c + " frame " + f + " at " + x + "," + y;
                        assertTrue(alpha == 0 || alpha == 255, at + ": alpha " + alpha + " (cutout only)");
                        if (alpha == 0) {
                            continue;
                        }
                        assertTrue(reserved.contains(rgb), at + ": " + Integer.toHexString(rgb) + " isn't reserved");
                        if (rgb == outline) {
                            continue;
                        }
                        // a coloured texel never meets a clear one inside its cell (a band's ends run to its edge)
                        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                            int nx = x + d[0];
                            int ny = y + d[1];
                            if (nx >= x0 && nx < x0 + c.w() && ny >= c.y() && ny < c.y() + c.h()) {
                                assertEquals(255, img.getRGB(nx, ny) >>> 24, at + " meets a clear texel: no outline");
                            }
                        }
                    }
                }
            }
        }
    }
}
