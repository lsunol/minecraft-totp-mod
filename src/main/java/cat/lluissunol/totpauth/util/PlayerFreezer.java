package cat.lluissunol.totpauth.util;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * Applies / removes the visual lockdown for unauthenticated players.
 *
 * <p>The hard interaction lockdown (movement, chat, commands, inventory, ...)
 * is enforced separately by {@code ServerGamePacketListenerImplMixin} and the
 * Fabric interaction callbacks. This class only handles the effects that stop a
 * frozen player from scouting the world.</p>
 */
public final class PlayerFreezer {

    /** -1 means infinite duration for a modern {@link MobEffectInstance}. */
    private static final int INFINITE = -1;

    private PlayerFreezer() {
    }

    // TODO(verify 1.21.11 Mojmap): confirm MobEffects.BLINDNESS / INVISIBILITY are Holder<MobEffect>
    // constants and that the 6-arg MobEffectInstance(effect, duration, amplifier, ambient, visible, showIcon)
    // constructor exists. Both have been stable from 1.20.5 through 1.21.x; adjust here if a drop changes them.
    public static void freeze(ServerPlayer player) {
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, INFINITE, 0, false, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, INFINITE, 0, false, false, false));
    }

    public static void unfreeze(ServerPlayer player) {
        player.removeEffect(MobEffects.BLINDNESS);
        player.removeEffect(MobEffects.INVISIBILITY);
    }
}
