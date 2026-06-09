package cat.lluissunol.totpauth.util;

import net.minecraft.server.level.ServerPlayer;

/**
 * Detects server-side "fake" players that are not real human logins and must
 * therefore bypass the 2FA gate.
 *
 * <p>The canonical case is Carpet mod's {@code /player <name> spawn} bots
 * ({@code carpet.patches.EntityPlayerMPFake}). They have no client behind them
 * to ever type {@code /2fa login}, so freezing them defeats their entire
 * purpose (automation, farms, ...).</p>
 *
 * <p>Carpet is an optional runtime dependency, so we deliberately avoid a hard
 * class reference and match by fully-qualified class name, walking the
 * superclass chain so subclasses (Carpet Extra, addons) are caught too.</p>
 */
public final class FakePlayers {

    private static final String CARPET_FAKE_PLAYER = "carpet.patches.EntityPlayerMPFake";

    private FakePlayers() {
    }

    public static boolean isFake(ServerPlayer player) {
        for (Class<?> c = player.getClass(); c != null && c != ServerPlayer.class; c = c.getSuperclass()) {
            if (CARPET_FAKE_PLAYER.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }
}
