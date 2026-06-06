package cat.lluissunol.totpauth.auth;

import java.util.UUID;

/**
 * Immutable in-memory view of a stored user.
 *
 * @param name        lowercase player name (the storage key)
 * @param uuid        offline-mode UUID derived from the player name
 * @param secret      Base32-encoded TOTP secret
 * @param state       {@link UserState#PENDING} or {@link UserState#ENROLLED}
 * @param createdAt   epoch millis when the secret was issued
 * @param confirmedAt epoch millis when enrollment was confirmed, or {@code null} while pending
 */
public record UserRecord(String name, UUID uuid, String secret, UserState state, long createdAt, Long confirmedAt) {

    /** Return a copy with a new state/confirmation timestamp, keeping everything else. */
    public UserRecord withState(UserState newState, Long newConfirmedAt) {
        return new UserRecord(name, uuid, secret, newState, createdAt, newConfirmedAt);
    }
}
