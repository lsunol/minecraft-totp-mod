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
    /** How many steps each side we accept (±1 step ≈ ±30s of clock drift). */
    private static final int ACCEPT_STEPS = 1;
    /** How wide we probe ONLY to diagnose a failure (±60 steps = ±30 min). */
    private static final int DIAGNOSE_STEPS = 60;

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
        Integer offset = matchOffsetSteps(base32Secret, submittedCode, ACCEPT_STEPS);
        return offset != null;
    }

    /**
     * Diagnose a code that failed {@link #verify}: probe a wide window (±30 min)
     * and report the step offset at which the code <em>would</em> match. A non-zero
     * result means the server clock is out of sync with the authenticator app:
     *
     * <ul>
     *   <li>positive = the server clock is <b>behind</b> real time (app is ahead)</li>
     *   <li>negative = the server clock is <b>ahead</b> of real time</li>
     *   <li>{@code null} = no match anywhere in the window, so the secret in the app
     *       differs from the stored one (wrong/old entry), not a clock problem</li>
     * </ul>
     *
     * @return signed step offset, or {@code null} if the code matches nowhere
     */
    public Integer diagnoseOffsetSteps(String base32Secret, String submittedCode) {
        return matchOffsetSteps(base32Secret, submittedCode, DIAGNOSE_STEPS);
    }

    /** Seconds represented by one step offset, for turning a diagnose result into a duration. */
    public long stepSeconds() {
        return STEP.toSeconds();
    }

    /**
     * Search {@code ±maxSteps} around now for a step whose code equals the submission.
     *
     * @return the signed offset of the first match (0 preferred), or {@code null} if none.
     */
    private Integer matchOffsetSteps(String base32Secret, String submittedCode, int maxSteps) {
        Integer submitted = parseCode(submittedCode);
        if (submitted == null || base32Secret == null) {
            return null;
        }

        final Key key;
        try {
            byte[] keyBytes = base32.decode(normalize(base32Secret));
            if (keyBytes.length == 0) {
                return null;
            }
            key = new SecretKeySpec(keyBytes, HMAC_ALGORITHM);
        } catch (RuntimeException e) {
            return null;
        }

        Instant now = clock.instant();
        try {
            // Widen outward from 0 so the smallest skew wins (and 0 is checked first).
            for (int distance = 0; distance <= maxSteps; distance++) {
                for (int step : distance == 0 ? new int[]{0} : new int[]{distance, -distance}) {
                    Instant timestamp = now.plus(STEP.multipliedBy(step));
                    if (totp.generateOneTimePassword(key, timestamp) == submitted) {
                        return step;
                    }
                }
            }
        } catch (InvalidKeyException e) {
            return null;
        }
        return null;
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

    /**
     * A trimmed otpauth:// URI carrying only the label and secret, so the QR
     * stays small enough to render as ASCII in chat. The omitted parameters
     * (issuer query, SHA1, 6 digits, 30s) are the universal authenticator
     * defaults, so scanning this yields the same configuration.
     */
    public String compactOtpauthUri(String accountName, String base32Secret) {
        return "otpauth://totp/" + urlEncode(ISSUER + ":" + accountName) + "?secret=" + base32Secret;
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
