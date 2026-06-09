package cat.lluissunol.totpauth.util;

import cat.lluissunol.totpauth.storage.FrozenPositionStore;
import cat.lluissunol.totpauth.storage.FrozenPositionStore.StoredPosition;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Sends unauthenticated players to a safe "limbo" high in the sky and brings
 * them back once they log in.
 *
 * <p>Cancelling movement packets alone is not enough: the client keeps
 * mispredicting (the player walks around on their own screen and only snaps back
 * on login) and, worse, their body stays in the real world where creepers and
 * mobs can still kill them. Instead we lift them straight up, switch off gravity
 * and make them invulnerable, so they are physically removed from harm with
 * nothing to scout or interact with until they authenticate.</p>
 *
 * <p>The genuine position is persisted via {@link FrozenPositionStore} so that a
 * disconnect or crash mid-limbo can never strand a player at the limbo
 * coordinates.</p>
 */
public final class Limbo {

    /** Height we lift frozen players to; entities live happily above the build limit. */
    private static final double LIMBO_Y = 500.0;

    private final FrozenPositionStore positions;

    public Limbo(FrozenPositionStore positions) {
        this.positions = positions;
    }

    /** Lift a player into limbo, remembering where they really were. */
    public void send(ServerPlayer player) {
        UUID uuid = player.getUUID();
        // Keep the first captured position. On a crash-recovery join the player
        // already loads at the limbo height, so we must not overwrite the real one.
        StoredPosition real = positions.get(uuid).orElseGet(() -> new StoredPosition(
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        if (!positions.contains(uuid)) {
            positions.put(uuid, real);
        }

        player.setNoGravity(true);
        player.setInvulnerable(true);
        PlayerFreezer.freeze(player);
        // Same dimension, straight up: keep X/Z, raise Y. connection.teleport syncs the client.
        player.connection.teleport(real.x(), LIMBO_Y, real.z(), real.yaw(), real.pitch());
    }

    /** Bring a freshly-authenticated player back to where they belong. */
    public void release(ServerPlayer player) {
        UUID uuid = player.getUUID();
        StoredPosition real = positions.get(uuid).orElse(null);

        PlayerFreezer.unfreeze(player);
        player.setNoGravity(false);
        player.setInvulnerable(false);
        if (real != null) {
            player.connection.teleport(real.x(), real.y(), real.z(), real.yaw(), real.pitch());
        }
        positions.remove(uuid);
    }

    /**
     * Best-effort cleanup when a still-frozen player disconnects: write the real
     * position and clear the limbo flags straight onto the entity so whatever is
     * saved to disk is clean, even if the mod is later removed. The stored
     * position is intentionally kept so the next join can re-verify and re-freeze.
     */
    public void restoreForSave(ServerPlayer player) {
        positions.get(player.getUUID()).ifPresent(real -> {
            PlayerFreezer.unfreeze(player);
            player.setNoGravity(false);
            player.setInvulnerable(false);
            player.setPos(real.x(), real.y(), real.z());
            player.setYRot(real.yaw());
            player.setXRot(real.pitch());
        });
    }
}
