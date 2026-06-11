package cat.lluissunol.totpauth.auth;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player rate limit for {@code /2fa login} attempts.
 *
 * <p>A 6-digit code has only a million values, so without a delay an attacker
 * could brute-force it by spamming the command. This caps each player to one
 * attempt per {@code delayMillis}, turning a guess flood into a crawl.</p>
 */
public final class LoginThrottle {

    private final Map<UUID, Long> lastAttempt = new ConcurrentHashMap<>();
    private final long delayMillis;

    public LoginThrottle(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    /**
     * Reserve an attempt slot for {@code uuid} at {@code now}.
     *
     * @return remaining millis to wait if still cooling down (attempt denied), or
     *         {@code 0} if allowed - in which case this attempt is recorded.
     */
    public long reserve(UUID uuid, long now) {
        if (delayMillis <= 0) {
            return 0;
        }
        Long previous = lastAttempt.get(uuid);
        if (previous != null && now - previous < delayMillis) {
            return delayMillis - (now - previous);
        }
        lastAttempt.put(uuid, now);
        return 0;
    }

    public void clear(UUID uuid) {
        lastAttempt.remove(uuid);
    }
}
