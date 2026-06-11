package cat.lluissunol.totpauth.mixin;

import cat.lluissunol.totpauth.TotpAuthMod;
import cat.lluissunol.totpauth.auth.SessionManager;
import cat.lluissunol.totpauth.util.Lang;
import cat.lluissunol.totpauth.util.Messages;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;

/**
 * Hard freeze for unauthenticated players, enforced at the packet boundary on
 * {@code net.minecraft.server.network.ServerGamePacketListenerImpl}.
 *
 * <p>The injected method names below are the official Mojang (Mojmap) names and
 * have been stable across 1.20.x - 1.21.x. If a future game drop renames one,
 * run {@code ./gradlew genSources} and update the {@code method = "..."} value.
 * Each injector is {@code defaultRequire = 1} (see totpauth.mixins.json), so a
 * stale name fails loudly at load instead of silently leaving a freeze gap.</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    @Shadow
    public ServerPlayer player;

    private boolean totpauth$frozen() {
        SessionManager sessions = TotpAuthMod.sessions();
        // Fail closed: if the mod is somehow not ready, treat the player as frozen.
        return sessions == null || !sessions.isAuthenticated(this.player.getUUID());
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void totpauth$onMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (totpauth$frozen()) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void totpauth$onPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        // Block breaking, dropping items and swapping hands.
        if (totpauth$frozen()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
    private void totpauth$onUseItemOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        // Right-clicking a block (placing, opening containers, ...).
        if (totpauth$frozen()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleUseItem", at = @At("HEAD"), cancellable = true)
    private void totpauth$onUseItem(ServerboundUseItemPacket packet, CallbackInfo ci) {
        // Right-clicking with an item in hand (eating, drawing a bow, throwing, ...).
        if (totpauth$frozen()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void totpauth$onInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        // Attacking or interacting with entities.
        if (totpauth$frozen()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void totpauth$onContainerClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        // Moving items inside any open menu, including the player's own inventory.
        if (totpauth$frozen()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void totpauth$onChat(ServerboundChatPacket packet, CallbackInfo ci) {
        if (totpauth$frozen()) {
            this.player.sendSystemMessage(Messages.warn(Lang.of(this.player), "totpauth.freeze.chat"));
            ci.cancel();
        }
    }

    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void totpauth$onChatCommand(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        if (totpauth$frozen() && !totpauth$isLoginCommand(packet.command())) {
            this.player.sendSystemMessage(Messages.warn(Lang.of(this.player), "totpauth.freeze.command"));
            ci.cancel();
        }
    }

    /** Only "/2fa login ..." may pass while a player is frozen. */
    private boolean totpauth$isLoginCommand(String command) {
        String normalized = command.strip().toLowerCase(Locale.ROOT);
        return normalized.equals("2fa login") || normalized.startsWith("2fa login ");
    }

    // TODO(verify 1.21.11 Mojmap): also intercept SIGNED commands.
    //   Since 1.20.5 the client sends command input as one of two packets:
    //     - ServerboundChatCommandPacket       -> handleChatCommand        (handled above; "/2fa login <code>" uses this one)
    //     - ServerboundChatCommandSignedPacket -> handleSignedChatCommand  (commands carrying SIGNED chat args, e.g. /msg)
    //   The exact Mojmap name of the signed handler ("handleSignedChatCommand" vs "handleChatCommandSigned")
    //   must be confirmed against the decompiled 1.21.11 sources (./gradlew genSources) before wiring it up,
    //   so it is intentionally left out rather than guessed. Impact while frozen is minor (no signed command is
    //   "/2fa login", and op-only commands still fail the permission check). Once verified, add:
    //
    //   @Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true)
    //   private void totpauth$onSignedChatCommand(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
    //       if (totpauth$frozen()) ci.cancel();
    //   }
}
