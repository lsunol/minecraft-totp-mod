package cat.lluissunol.totpauth.mixin;

import cat.lluissunol.totpauth.TotpAuthMod;
import cat.lluissunol.totpauth.TotpConfig;
import cat.lluissunol.totpauth.util.MojangAccounts;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets players whose claimed username matches a real Mojang/Microsoft account go through
 * vanilla's own online-mode verification (RSA/AES handshake + session-server check) instead
 * of the server's configured (offline) mode, so {@code TotpAuthMod} can skip TOTP for them -
 * their account is already protected by Mojang, and only the genuine owner can complete that
 * handshake.
 *
 * <p>Redirects the single {@code MinecraftServer.usesAuthentication()} call inside
 * {@code handleHello}: when it would normally return {@code false} (server running
 * {@code online-mode=false}), this also returns {@code true} for that one connection if
 * {@code premiumAutoLoginEnabled} is on and the requested username resolves to a real Mojang
 * account. Every other connection is unaffected and follows the existing offline+TOTP path.
 * Reusing vanilla's verification code unmodified means no cryptography is reimplemented here.</p>
 *
 * <p><b>Note:</b> if the subsequent handshake fails (e.g. a cracked client merely claiming a
 * premium username), vanilla hard-disconnects the connection ("Failed to verify username!")
 * rather than falling back to TOTP - which is intentional, since letting that fall through
 * would let anyone register a TOTP account under a premium player's name.</p>
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {

    @Shadow
    String requestedUsername;

    @Redirect(method = "handleHello",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"))
    private boolean totpauth$usesAuthenticationOrPremiumName(MinecraftServer server) {
        if (server.usesAuthentication()) {
            return true;
        }
        TotpConfig config = TotpAuthMod.config();
        String username = this.requestedUsername;
        if (config == null || !config.premiumAutoLoginEnabled() || username == null) {
            return false;
        }
        return MojangAccounts.exists(username);
    }
}
