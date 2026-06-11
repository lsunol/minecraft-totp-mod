package cat.lluissunol.totpauth.auth;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which players have authenticated during their CURRENT login session.
 *
 * <p>This is intentionally in-memory only: nothing here is persisted, so a player
 * must re-authenticate on every join unless {@code TotpAuthMod.onJoin} re-grants
 * the session up front via the trusted-IP grace window. Reads happen from the
 * network thread (via the packet-handler mixin), hence the concurrent set.</p>
 */
public final class SessionManager {

    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();

    public boolean isAuthenticated(UUID uuid) {
        return authenticated.contains(uuid);
    }

    public void authenticate(UUID uuid) {
        authenticated.add(uuid);
    }

    public void deauthenticate(UUID uuid) {
        authenticated.remove(uuid);
    }
}
