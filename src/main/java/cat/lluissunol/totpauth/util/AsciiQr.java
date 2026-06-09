package cat.lluissunol.totpauth.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a QR code as chat lines using Unicode half-block characters.
 *
 * <p>Two module rows are packed into one text line ({@code ▀}/{@code ▄}/{@code █}),
 * keeping the symbol roughly square. Every cell uses the full-block glyph {@code █}
 * so all columns have identical width; light modules are WHITE and dark modules are
 * BLACK, giving a readable dark-on-light QR with a uniform quiet zone.</p>
 */
public final class AsciiQr {

    /** Light border (in modules) drawn around the symbol so scanners can find it. */
    private static final int QUIET_ZONE = 2;

    // All cells use █ (U+2588) as the base glyph; half-blocks encode mixed rows.
    // Using the same glyph for every cell ensures columns never vary in width.
    private static final char FULL  = '█'; // U+2588 – both halves same color
    private static final char UPPER = '▀'; // U+2580 – top light (WHITE), bottom dark (BLACK bg)
    private static final char LOWER = '▄'; // U+2584 – top dark (BLACK bg), bottom light (WHITE)

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
            MutableComponent line = Component.empty();
            StringBuilder batch = new StringBuilder();
            ChatFormatting batchColor = null;

            for (int col = 0; col < dim; col++) {
                boolean topLight = !dark(qr, row, col, size);
                boolean bottomLight = (row + 1 >= dim) || !dark(qr, row + 1, col, size);
                char ch;
                ChatFormatting color;
                if (topLight && bottomLight) {
                    ch = FULL;  color = ChatFormatting.WHITE;
                } else if (topLight) {
                    ch = UPPER; color = ChatFormatting.WHITE;
                } else if (bottomLight) {
                    ch = LOWER; color = ChatFormatting.WHITE;
                } else {
                    ch = FULL;  color = ChatFormatting.BLACK;
                }

                if (color == batchColor) {
                    batch.append(ch);
                } else {
                    if (batchColor != null) {
                        final ChatFormatting fc = batchColor;
                        final String text = batch.toString();
                        line.append(Component.literal(text)
                                .withStyle(s -> s.withFont(uniform).applyFormat(fc)));
                    }
                    batch = new StringBuilder();
                    batch.append(ch);
                    batchColor = color;
                }
            }
            if (batchColor != null && !batch.isEmpty()) {
                final ChatFormatting fc = batchColor;
                final String text = batch.toString();
                line.append(Component.literal(text)
                        .withStyle(s -> s.withFont(uniform).applyFormat(fc)));
            }
            lines.add(line);
        }
        return lines;
    }

    private static boolean dark(boolean[][] qr, int row, int col, int size) {
        int r = row - QUIET_ZONE;
        int c = col - QUIET_ZONE;
        // Outside the symbol = quiet zone (light).
        return r >= 0 && c >= 0 && r < size && c < size && qr[r][c];
    }
}
