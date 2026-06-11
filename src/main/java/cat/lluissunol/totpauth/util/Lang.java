package cat.lluissunol.totpauth.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side localization.
 *
 * <p>This is a server-only mod played with vanilla clients, so Minecraft's
 * {@code Component.translatable} can't be used (the client has no language files
 * for our keys and would just render the raw key). Instead the server keeps the
 * translation tables itself, reads each player's selected client language
 * ({@link ServerPlayer#clientInformation()}.language()), and sends already
 * translated plain text. Every recipient therefore sees messages in their own
 * client language - including ops being notified about another player.</p>
 *
 * <p>Tables are bundled JSON under {@code assets/totpauth/lang/&lt;code&gt;.json}.
 * Adding a language is just dropping another file and listing it in
 * {@link #BUNDLED}. {@code en_us} is the fallback and must define every key.</p>
 */
public final class Lang {

    /** Last-resort language; its table must contain every key used in code. */
    private static final String ULTIMATE = "en_us";

    /** Bundled language codes (lowercase), in resolution-priority order. */
    private static final String[] BUNDLED = {"en_us", "es_es", "ca_es"};

    private static final Gson GSON = new Gson();
    private static final Type STRING_MAP = new TypeToken<Map<String, String>>() { }.getType();

    /** code -> (key -> template); populated once at startup, read-only thereafter. */
    private static final Map<String, Map<String, String>> TABLES = new LinkedHashMap<>();

    /** Configured default: used for the console and for clients whose language we don't bundle. */
    private static volatile String defaultLanguage = ULTIMATE;

    private Lang() {
    }

    /** The configured default language (console + unknown-client fallback). */
    public static String defaultLanguage() {
        return defaultLanguage;
    }

    /** Load every bundled table from the classpath and set the default language. Call once on init. */
    public static void load(Logger logger, String configuredDefault) {
        defaultLanguage = configuredDefault == null || configuredDefault.isBlank()
                ? ULTIMATE : configuredDefault.toLowerCase(Locale.ROOT);
        TABLES.clear();
        for (String code : BUNDLED) {
            String path = "/assets/totpauth/lang/" + code + ".json";
            try (InputStream in = Lang.class.getResourceAsStream(path)) {
                if (in == null) {
                    logger.warn("[TotpAuth] Missing bundled language file {}", path);
                    continue;
                }
                Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
                Map<String, String> table = GSON.fromJson(reader, STRING_MAP);
                if (table != null) {
                    TABLES.put(code, table);
                }
            } catch (Exception e) {
                logger.error("[TotpAuth] Failed to load language file {}", path, e);
            }
        }
        if (!TABLES.containsKey(ULTIMATE)) {
            logger.error("[TotpAuth] Fallback language {} failed to load - messages may show raw keys.", ULTIMATE);
        }
        logger.info("[TotpAuth] Loaded {} language(s) {}; default={}", TABLES.size(), TABLES.keySet(), defaultLanguage);
    }

    /** The (lowercased) client language of a player, or the configured default for none. */
    public static String of(ServerPlayer player) {
        if (player == null) {
            return defaultLanguage;
        }
        String language = player.clientInformation().language();
        return language == null ? defaultLanguage : language.toLowerCase(Locale.ROOT);
    }

    /** The recipient language for a command source (the player's, or the default for the console). */
    public static String of(CommandSourceStack source) {
        return of(source.getPlayer());
    }

    /**
     * Resolve {@code key} in {@code language}, falling back to the language family,
     * then the configured default, then {@code en_us}, then the key itself, and
     * format it with {@code args} via {@link String#format}.
     */
    public static String get(String language, String key, Object... args) {
        String template = lookup(language, key);
        if (template == null) {
            template = lookup(defaultLanguage, key);
        }
        if (template == null) {
            template = lookup(ULTIMATE, key);
        }
        if (template == null) {
            return key;
        }
        if (args == null || args.length == 0) {
            return template;
        }
        try {
            return String.format(template, args);
        } catch (RuntimeException e) {
            return template;
        }
    }

    private static String lookup(String language, String key) {
        if (language == null) {
            return null;
        }
        String code = language.toLowerCase(Locale.ROOT);
        Map<String, String> table = TABLES.get(code);
        if (table == null) {
            // e.g. "es_mx" -> first bundled "es_*"; "en_gb" -> "en_us".
            String prefix = code.length() >= 2 ? code.substring(0, 2) : code;
            for (Map.Entry<String, Map<String, String>> entry : TABLES.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    table = entry.getValue();
                    break;
                }
            }
        }
        return table == null ? null : table.get(key);
    }
}
