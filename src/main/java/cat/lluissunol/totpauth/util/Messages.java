package cat.lluissunol.totpauth.util;

import cat.lluissunol.totpauth.totp.TotpService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
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

    /**
     * Privately reveal a freshly-issued secret plus setup instructions to the
     * player: a scannable ASCII QR code, the secret and a setup link (both
     * click-to-copy), and a click-to-suggest login command.
     */
    public static void sendSecret(ServerPlayer player, TotpService totp, String secret) {
        String account = player.nameAndId().name();
        String fullUri = totp.otpauthUri(account, secret);
        String qrUri = totp.compactOtpauthUri(account, secret);

        player.sendSystemMessage(warn("An admin approved your access. Set up two-factor authentication:"));
        player.sendSystemMessage(info("1. Open Google Authenticator / Authy / FreeOTP."));
        player.sendSystemMessage(info("2. Scan this QR code (or use the key / link beneath it):"));
        for (Component line : AsciiQr.render(qrUri)) {
            player.sendSystemMessage(line);
        }
        player.sendSystemMessage(Component.literal(PREFIX + "   Secret: ")
                .withStyle(ChatFormatting.GRAY)
                .append(copyable(totp.formatForDisplay(secret), secret,
                        ChatFormatting.AQUA, ChatFormatting.BOLD)));
        player.sendSystemMessage(Component.literal(PREFIX + "   Setup link: ")
                .withStyle(ChatFormatting.GRAY)
                .append(copyable("[click to copy]", fullUri,
                        ChatFormatting.DARK_AQUA, ChatFormatting.UNDERLINE)));
        player.sendSystemMessage(Component.literal(PREFIX + "3. Then run ")
                .withStyle(ChatFormatting.GREEN)
                .append(suggest("/2fa login <code>", "/2fa login ")));
    }

    /** A label that copies {@code clipboard} to the system clipboard when clicked. */
    private static MutableComponent copyable(String label, String clipboard, ChatFormatting... formats) {
        return Component.literal(label).withStyle(style -> style
                .applyFormats(formats)
                .withClickEvent(new ClickEvent.CopyToClipboard(clipboard))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy"))));
    }

    /** A label that pre-fills the chat box with {@code command} when clicked. */
    private static MutableComponent suggest(String label, String command) {
        return Component.literal(label).withStyle(style -> style
                .applyFormat(ChatFormatting.GREEN)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to put this in your chat box"))));
    }
}
