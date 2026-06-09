package cat.lluissunol.totpauth.util;

import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;

import java.util.EnumMap;
import java.util.Map;

/**
 * Thin wrapper over zxing that turns a string into a raw QR module matrix.
 *
 * <p>We only use the encoder half of zxing and return the bare symbol (no quiet
 * zone, no scaling); callers add their own border when rendering.</p>
 */
public final class QrEncoder {

    private QrEncoder() {
    }

    /**
     * Encode {@code content} into a square QR module matrix.
     *
     * @return {@code matrix[row][col]}, {@code true} where a module is dark, or
     *         {@code null} if the content cannot be encoded.
     */
    public static boolean[][] encode(String content) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        // Level L keeps the symbol small (it renders close-up on a screen, so the
        // extra redundancy of higher levels is unnecessary and only adds modules).
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        try {
            QRCode qr = Encoder.encode(content, ErrorCorrectionLevel.L, hints);
            ByteMatrix matrix = qr.getMatrix();
            if (matrix == null) {
                return null;
            }
            int height = matrix.getHeight();
            int width = matrix.getWidth();
            boolean[][] out = new boolean[height][width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    out[y][x] = matrix.get(x, y) == 1;
                }
            }
            return out;
        } catch (WriterException e) {
            return null;
        }
    }
}
