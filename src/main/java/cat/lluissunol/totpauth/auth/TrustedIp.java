package cat.lluissunol.totpauth.auth;

import java.time.Duration;

/**
 * Trusted-IP grace window.
 *
 * <p>After an {@link UserState#ENROLLED} player passes a TOTP check, the IP they
 * authenticated from is remembered on their {@link UserRecord}. While that IP is
 * still trusted, rejoining from it skips the TOTP prompt entirely. The window is
 * measured from the last successful {@code /2fa login} (it is <em>not</em> slid
 * forward by the automatic trusted-IP logins), so an enrolled player re-enters a
 * code at most once per window.</p>
 *
 * <p>This is a deliberate convenience/security trade-off chosen by the server
 * owner; trust is per-player and cleared by {@code /2fa reset} (and by re-issuing
 * a secret with {@code /2fa approve}).</p>
 */
public final class TrustedIp {

    /** How long an IP stays trusted after a successful TOTP login. */
    public static final long WINDOW_MILLIS = Duration.ofDays(7).toMillis();

    private TrustedIp() {
    }

    /**
     * Whether {@code ip} should be auto-authenticated for {@code record}: it must
     * match the record's remembered IP and fall inside the trust window.
     */
    public static boolean isValid(UserRecord record, String ip, long now) {
        if (ip == null || record.trustedIp() == null || record.trustedIpAt() == null) {
            return false;
        }
        return ip.equals(record.trustedIp()) && now - record.trustedIpAt() <= WINDOW_MILLIS;
    }
}
