package cat.lluissunol.totpauth.totp;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import org.apache.commons.codec.binary.Base32;

import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * RFC 6238 TOTP service: HMAC-SHA1, 6 digits, 30-second time step.
 *
 * <p>This matches the defaults of Google Authenticator, Authy, FreeOTP, etc.
 * Secrets are 160-bit (20-byte) random values, which encode to exactly 32
 * Base32 characters with no padding.</p>
 */
public final class TotpService {

    public static final String ISSUER = "TotpAuth";

    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final int DIGITS = 6;
    private static final Duration STEP = Duration.ofSeconds(30);
    /** RFC 4226 recommends a 160-bit key for HMAC-SHA1. */
    private static final int SECRET_BYTES = 20;

    private final TimeBasedOneTimePasswordGenerator totp;
    private final SecureRandom random = new SecureRandom();
    private final Base32 base32 = new Base32();
    private final Clock clock;

    public TotpService() {
        this(Clock.systemUTC());
    }

    /** Constructor with an injectable clock, mostly to make verification testable. */
    public TotpService(Clock clock) {
        this.clock = clock;
        // The (Duration, int, String) constructor only declares the UNCHECKED
        // UncheckedNoSuchAlgorithmException; HmacSHA1 is mandated by every JVM.
        this.totp = new TimeBasedOneTimePasswordGenerator(STEP, DIGITS, HMAC_ALGORITHM);
    }

    /** Generate a fresh random secret, returned as an unpadded uppercase Base32 string. */
    public String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return base32.encodeAsString(bytes).replace("=", "").toUpperCase(Locale.ROOT);
    }

    /**
     * Verify a submitted code against the secret, accepting the current 30s step
     * and +/-1 step (tolerating roughly +/-30s of clock drift).
     *
     * @return {@code true} only if the code matches; malformed input yields {@code false}.
     */
    public boolean verify(String base32Secret, String submittedCode) {
        Integer submitted = parseCode(submittedCode);
        if (submitted == null || base32Secret == null) {
            return false;
        }

        final Key key;
        try {
            byte[] keyBytes = base32.decode(normalize(base32Secret));
            if (keyBytes.length == 0) {
                return false;
            }
            key = new SecretKeySpec(keyBytes, HMAC_ALGORITHM);
        } catch (RuntimeException e) {
            return false;
        }

        Instant now = clock.instant();
        try {
            for (int step = -1; step <= 1; step++) {
                Instant timestamp = now.plus(STEP.multipliedBy(step));
                if (totp.generateOneTimePassword(key, timestamp) == submitted) {
                    return true;
                }
            }
        } catch (InvalidKeyException e) {
            return false;
        }
        return false;
    }

    /** otpauth:// URI suitable for pasting into an authenticator app or rendering as a QR code. */
    public String otpauthUri(String accountName, String base32Secret) {
        String label = urlEncode(ISSUER + ":" + accountName);
        return "otpauth://totp/" + label
                + "?secret=" + base32Secret
                + "&issuer=" + urlEncode(ISSUER)
                + "&algorithm=SHA1&digits=" + DIGITS
                + "&period=" + STEP.toSeconds();
    }

    /** Group a secret into blocks of four characters for easier manual entry. */
    public String formatForDisplay(String base32Secret) {
        return base32Secret.replaceAll("(.{4})(?=.)", "$1 ");
    }

    private String normalize(String secret) {
        return secret.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private Integer parseCode(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\s+", "");
        if (digits.isEmpty() || digits.length() > DIGITS || !digits.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
