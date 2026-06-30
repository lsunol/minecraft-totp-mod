package cat.lluissunol.totpauth.util;

import cat.lluissunol.totpauth.TotpAuthMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Looks up whether a username currently belongs to a real Mojang/Microsoft account, via
 * Mojang's public profile API.
 *
 * <p>This is only used to DECIDE whether a connecting client should be challenged with
 * real online-mode verification (see {@code ServerLoginPacketListenerImplMixin}) - it is
 * never treated as proof of identity by itself. The actual proof is the vanilla
 * session-server handshake that only the genuine account holder can complete; a client
 * that merely picks a matching username but can't pass that handshake is disconnected,
 * never let in.</p>
 *
 * <p>Runs synchronously on the login packet thread, so lookups use a short timeout and
 * results are cached briefly to bound worst-case latency.</p>
 */
public final class MojangAccounts {

    private static final String API = "https://api.mojang.com/users/profiles/minecraft/";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final long CACHE_TTL_MILLIS = Duration.ofMinutes(10).toMillis();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private MojangAccounts() {
    }

    /** Whether {@code username} currently belongs to a real Mojang/Microsoft account. */
    public static boolean exists(String username) {
        String key = username.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        CacheEntry cached = CACHE.get(key);
        if (cached != null && now - cached.checkedAt < CACHE_TTL_MILLIS) {
            return cached.exists;
        }
        boolean exists = lookup(username);
        CACHE.put(key, new CacheEntry(exists, now));
        return exists;
    }

    private static boolean lookup(String username) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(API + username))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            // Network hiccup or Mojang outage: don't challenge the connection, so it falls
            // back to the normal offline+TOTP path instead of being treated as premium.
            TotpAuthMod.LOGGER.warn("[TotpAuth] Mojang account lookup failed for {} - treating as non-premium.",
                    username, e);
            return false;
        }
    }

    private static final class CacheEntry {
        final boolean exists;
        final long checkedAt;

        CacheEntry(boolean exists, long checkedAt) {
            this.exists = exists;
            this.checkedAt = checkedAt;
        }
    }
}
