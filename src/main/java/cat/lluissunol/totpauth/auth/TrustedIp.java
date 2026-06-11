package cat.lluissunol.totpauth.auth;

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
 * <p>The window length is configurable ({@code trustedIpWindowDays}); this is a
 * deliberate convenience/security trade-off chosen by the server owner. Trust is
 * per-player and cleared by {@code /2fa reset} (and by re-issuing a secret with
 * {@code /2fa approve}).</p>
 */
public final class TrustedIp {

    private TrustedIp() {
    }

    /**
     * Whether {@code ip} should be auto-authenticated for {@code record}: it must
     * match the record's remembered IP and fall inside {@code windowMillis} of the
     * last login. A {@code windowMillis} of 0 disables the grace entirely.
     */
    public static boolean isValid(UserRecord record, String ip, long now, long windowMillis) {
        if (windowMillis <= 0 || ip == null || record.trustedIp() == null || record.trustedIpAt() == null) {
            return false;
        }
        return ip.equals(record.trustedIp()) && now - record.trustedIpAt() <= windowMillis;
    }
}
