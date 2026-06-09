package cat.lluissunol.totpauth.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a QR code as chat lines using Unicode half-block characters.
 *
 * <p>Two module rows are packed into one text line ({@code U+2580}/{@code U+2584}/
 * {@code U+2588}), which keeps the code roughly square. Light modules are drawn
 * as bright glyphs and dark modules as the (dark) chat background, so the result
 * reads as a normal dark-on-light code; a light quiet zone is added so scanners
 * can lock onto the finder patterns. The whole thing is forced into the fixed
 * width {@code minecraft:uniform} font so the columns line up.</p>
 *
 * <p>Chat is not a monospaced canvas, so this is a best-effort convenience: the
 * click-to-copy setup link remains the reliable path.</p>
 */
public final class AsciiQr {

    /** Light border (in modules) drawn around the symbol so scanners can find it. */
    private static final int QUIET_ZONE = 2;

    private static final char BOTH = '█';  // full block  - both halves light
    private static final char UPPER = '▀'; // upper half  - top light, bottom dark
    private static final char LOWER = '▄'; // lower half  - top dark, bottom light
    private static final char NONE = ' ';       //             - both halves dark

    private AsciiQr() {
    }

    /** Encode {@code content} and render it as a list of chat lines (empty if encoding fails). */
    public static List<Component> render(String content) {
        boolean[][] qr = QrEncoder.encode(content);
        if (qr == null) {
            return List.of();
        }
        FontDescription uniform = new FontDescription.Resource(Identifier.withDefaultNamespace("uniform"));
        int size = qr.length;
        int dim = size + QUIET_ZONE * 2;

        List<Component> lines = new ArrayList<>();
        for (int row = 0; row < dim; row += 2) {
            StringBuilder sb = new StringBuilder(dim);
            for (int col = 0; col < dim; col++) {
                boolean topLight = !dark(qr, row, col, size);
                boolean bottomLight = (row + 1 >= dim) || !dark(qr, row + 1, col, size);
                sb.append(glyph(topLight, bottomLight));
            }
            lines.add(Component.literal(sb.toString())
                    .withStyle(style -> style.withFont(uniform).applyFormat(ChatFormatting.WHITE)));
        }
        return lines;
    }

    private static boolean dark(boolean[][] qr, int row, int col, int size) {
        int r = row - QUIET_ZONE;
        int c = col - QUIET_ZONE;
        // Anything outside the symbol is the (light) quiet zone.
        return r >= 0 && c >= 0 && r < size && c < size && qr[r][c];
    }

    private static char glyph(boolean topLight, boolean bottomLight) {
        if (topLight && bottomLight) {
            return BOTH;
        }
        if (topLight) {
            return UPPER;
        }
        if (bottomLight) {
            return LOWER;
        }
        return NONE;
    }
}
