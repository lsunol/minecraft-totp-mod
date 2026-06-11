package cat.lluissunol.totpauth.util;

import cat.lluissunol.totpauth.totp.TotpService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * Builds consistently-prefixed, localized chat messages.
 *
 * <p>Each builder takes the recipient's language code (see {@link Lang}) plus a
 * translation key, so the text is resolved in that player's client language.</p>
 */
public final class Messages {

    public static final String PREFIX = "[TotpAuth] ";

    private Messages() {
    }

    public static Component info(String lang, String key, Object... args) {
        return line(ChatFormatting.GRAY, lang, key, args);
    }

    public static Component good(String lang, String key, Object... args) {
        return line(ChatFormatting.GREEN, lang, key, args);
    }

    public static Component warn(String lang, String key, Object... args) {
        return line(ChatFormatting.YELLOW, lang, key, args);
    }

    public static Component error(String lang, String key, Object... args) {
        return line(ChatFormatting.RED, lang, key, args);
    }

    private static Component line(ChatFormatting color, String lang, String key, Object... args) {
        return Component.literal(PREFIX + Lang.get(lang, key, args)).withStyle(color);
    }

    /**
     * Privately reveal a freshly-issued secret plus setup instructions to the
     * player, in their client language: a scannable ASCII QR code, the secret and
     * a setup link (both click-to-copy), and a click-to-suggest login command.
     */
    public static void sendSecret(ServerPlayer player, TotpService totp, String secret) {
        String lang = Lang.of(player);
        String account = player.nameAndId().name();
        String fullUri = totp.otpauthUri(account, secret);
        String qrUri = totp.compactOtpauthUri(account, secret);

        player.sendSystemMessage(warn(lang, "totpauth.secret.intro"));
        player.sendSystemMessage(info(lang, "totpauth.secret.step1"));
        player.sendSystemMessage(info(lang, "totpauth.secret.step2"));
        for (Component qrLine : AsciiQr.render(qrUri)) {
            player.sendSystemMessage(qrLine);
        }
        player.sendSystemMessage(Component.literal(PREFIX + "   " + Lang.get(lang, "totpauth.secret.label_secret"))
                .withStyle(ChatFormatting.GRAY)
                .append(copyable(totp.formatForDisplay(secret), secret, lang,
                        ChatFormatting.AQUA, ChatFormatting.BOLD)));
        player.sendSystemMessage(Component.literal(PREFIX + "   " + Lang.get(lang, "totpauth.secret.label_link"))
                .withStyle(ChatFormatting.GRAY)
                .append(copyable(Lang.get(lang, "totpauth.secret.link_click"), fullUri, lang,
                        ChatFormatting.DARK_AQUA, ChatFormatting.UNDERLINE)));
        player.sendSystemMessage(Component.literal(PREFIX + Lang.get(lang, "totpauth.secret.step3"))
                .withStyle(ChatFormatting.GREEN)
                .append(suggest(Lang.get(lang, "totpauth.secret.login_label"), "/2fa login ", lang)));
    }

    /** A label that copies {@code clipboard} to the system clipboard when clicked. */
    private static MutableComponent copyable(String label, String clipboard, String lang, ChatFormatting... formats) {
        return Component.literal(label).withStyle(style -> style
                .applyFormats(formats)
                .withClickEvent(new ClickEvent.CopyToClipboard(clipboard))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(Lang.get(lang, "totpauth.hover.copy")))));
    }

    /** A label that pre-fills the chat box with {@code command} when clicked. */
    private static MutableComponent suggest(String label, String command, String lang) {
        return Component.literal(label).withStyle(style -> style
                .applyFormat(ChatFormatting.GREEN)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(Lang.get(lang, "totpauth.hover.suggest")))));
    }
}
