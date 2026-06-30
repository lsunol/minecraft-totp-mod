package cat.lluissunol.totpauth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;

/**
 * Editable server configuration, persisted as {@code config/totpauth/config.json}.
 *
 * <p>Field names are the JSON keys. Missing fields keep their default (Gson only
 * overwrites what is present), so the file is forward-compatible; on every load
 * it is rewritten to fill in any newly-added keys with their defaults.</p>
 */
public final class TotpConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Language used for the console and for clients whose language we don't bundle. */
    private String defaultLanguage = "ca_es";

    /** Days an IP stays trusted after a successful TOTP login (0 disables the grace window). */
    private int trustedIpWindowDays = 7;

    /** Minimum delay between two {@code /2fa login} attempts by the same player (anti-brute-force). */
    private long loginAttemptDelayMillis = 1000;

    /** Kick a still-unauthenticated player after this many seconds (0 disables the kick). */
    private int unauthenticatedKickSeconds = 60;

    /** Height (Y) frozen players are lifted to while in limbo. */
    private double limboHeight = 500.0;

    /**
     * Let players whose connection completes real Mojang/Microsoft verification skip TOTP
     * entirely - their account is already protected by Mojang, so a second factor is
     * redundant. Requires {@code online-mode=false} in server.properties (cracked players
     * still need TOTP); see {@code ServerLoginPacketListenerImplMixin}.
     */
    private boolean premiumAutoLoginEnabled = false;

    public static TotpConfig load(Path file, Logger logger) {
        TotpConfig config = new TotpConfig();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                TotpConfig parsed = GSON.fromJson(reader, TotpConfig.class);
                if (parsed != null) {
                    config = parsed;
                }
            } catch (Exception e) {
                logger.error("[TotpAuth] Could not read config {} - using defaults.", file, e);
            }
        }
        config.save(file, logger);
        logger.info("[TotpAuth] Config: language={}, trustedIpDays={}, loginDelayMs={}, kickSeconds={}, limboY={}, "
                        + "premiumAutoLogin={}",
                config.defaultLanguage(), config.trustedIpWindowDays, config.loginAttemptDelayMillis(),
                config.unauthenticatedKickSeconds(), config.limboHeight, config.premiumAutoLoginEnabled());
        return config;
    }

    private void save(Path file, Logger logger) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.error("[TotpAuth] Could not write config {}.", file, e);
        }
    }

    public String defaultLanguage() {
        return defaultLanguage == null || defaultLanguage.isBlank()
                ? "en_us" : defaultLanguage.toLowerCase(Locale.ROOT);
    }

    public long trustedIpWindowMillis() {
        return Duration.ofDays(Math.max(0, trustedIpWindowDays)).toMillis();
    }

    public long loginAttemptDelayMillis() {
        return Math.max(0, loginAttemptDelayMillis);
    }

    public int unauthenticatedKickSeconds() {
        return Math.max(0, unauthenticatedKickSeconds);
    }

    public double limboHeight() {
        return limboHeight;
    }

    public boolean premiumAutoLoginEnabled() {
        return premiumAutoLoginEnabled;
    }
}
