package cat.lluissunol.totpauth.util;

import cat.lluissunol.totpauth.totp.TotpService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Small helpers for building consistently-prefixed chat messages. */
public final class Messages {

    public static final String PREFIX = "[TotpAuth] ";

    private Messages() {
    }

    public static Component info(String text) {
        return Component.literal(PREFIX + text).withStyle(ChatFormatting.GRAY);
    }

    public static Component good(String text) {
        return Component.literal(PREFIX + text).withStyle(ChatFormatting.GREEN);
    }

    public static Component warn(String text) {
        return Component.literal(PREFIX + text).withStyle(ChatFormatting.YELLOW);
    }

    public static Component error(String text) {
        return Component.literal(PREFIX + text).withStyle(ChatFormatting.RED);
    }

    /** Privately reveal a freshly-issued secret plus setup instructions to the player. */
    public static void sendSecret(ServerPlayer player, TotpService totp, String secret) {
        String account = player.nameAndId().name();
        player.sendSystemMessage(warn("An admin approved your access. Set up two-factor authentication:"));
        player.sendSystemMessage(info("1. Open Google Authenticator / Authy / FreeOTP."));
        player.sendSystemMessage(info("2. Add an account using a setup key (type: TOTP, SHA1, 6 digits, 30s)."));
        player.sendSystemMessage(Component.literal(PREFIX + "   Secret: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(totp.formatForDisplay(secret))
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
        player.sendSystemMessage(Component.literal(PREFIX + "   Or import this link: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(totp.otpauthUri(account, secret))
                        .withStyle(ChatFormatting.DARK_AQUA)));
        player.sendSystemMessage(good("3. Then run:  /2fa login <code>"));
    }
}
