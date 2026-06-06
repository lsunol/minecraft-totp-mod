package cat.lluissunol.totpauth.auth;

/**
 * Lifecycle of a player with respect to TOTP authentication.
 *
 * <p>Only {@link #PENDING} and {@link #ENROLLED} are ever persisted to disk;
 * {@link #UNREGISTERED} is simply the absence of a stored record.</p>
 */
public enum UserState {
    /** No record exists for this player yet. */
    UNREGISTERED,
    /** A secret has been generated but the player has not yet confirmed a first valid code. */
    PENDING,
    /** The player confirmed enrollment; they must submit a valid code on every join. */
    ENROLLED
}
