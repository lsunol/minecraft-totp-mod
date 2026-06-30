package cat.lluissunol.totpauth;

import cat.lluissunol.totpauth.auth.LoginThrottle;
import cat.lluissunol.totpauth.auth.SessionManager;
import cat.lluissunol.totpauth.auth.TrustedIp;
import cat.lluissunol.totpauth.auth.UserRecord;
import cat.lluissunol.totpauth.auth.UserState;
import cat.lluissunol.totpauth.command.TotpCommand;
import cat.lluissunol.totpauth.storage.FrozenPositionStore;
import cat.lluissunol.totpauth.storage.UserStore;
import cat.lluissunol.totpauth.totp.TotpService;
import cat.lluissunol.totpauth.util.FakePlayers;
import cat.lluissunol.totpauth.util.Lang;
import cat.lluissunol.totpauth.util.Limbo;
import cat.lluissunol.totpauth.util.Messages;
import cat.lluissunol.totpauth.util.OfflineUuid;
import cat.lluissunol.totpauth.util.PlayerIp;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side TOTP authentication gate.
 *
 * <p>On an offline-mode server every (real) player is sent to a safe limbo on
 * join until they submit a valid TOTP code via {@code /2fa login}. New players
 * must be approved by an admin (or the console) with {@code /2fa approve}.
 * Server-side fake players (e.g. Carpet bots) bypass the gate entirely.</p>
 */
public final class TotpAuthMod implements ModInitializer {

    public static final String MOD_ID = "totpauth";
    public static final Logger LOGGER = LoggerFactory.getLogger("TotpAuth");

    /** Held statically so the packet-handler mixins can query session state and config. */
    private static SessionManager sessions;
    private static TotpConfig config;

    private UserStore store;
    private TotpService totp;
    private Limbo limbo;

    /** Tracks when each unauthenticated player joined, for the kick-timeout. */
    private final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        sessions = new SessionManager();
        totp = new TotpService();

        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        config = TotpConfig.load(configDir.resolve("config.json"), LOGGER);
        Lang.load(LOGGER, config.defaultLanguage());

        store = new UserStore(configDir.resolve("users.json"), LOGGER);
        store.load();

        FrozenPositionStore frozenPositions =
                new FrozenPositionStore(configDir.resolve("frozen_positions.json"), LOGGER);
        frozenPositions.load();
        limbo = new Limbo(frozenPositions, config.limboHeight());

        LoginThrottle throttle = new LoginThrottle(config.loginAttemptDelayMillis());
        registerCommands(throttle);
        registerInteractionGuards();
        registerJoinLeave();
        registerKickTimeout();

        LOGGER.info("[TotpAuth] Initialised. User store: {}", configDir.resolve("users.json"));
    }

    public static SessionManager sessions() {
        return sessions;
    }

    public static TotpConfig config() {
        return config;
    }

    private void registerCommands(LoginThrottle throttle) {
        TotpCommand command = new TotpCommand(store, sessions, totp, limbo, throttle);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                command.register(dispatcher));
    }

    /**
     * Block interactions for frozen players. Returning {@link InteractionResult#FAIL} cancels
     * the interaction; {@code PASS} lets vanilla handle it. The packet-handler mixin covers
     * movement, inventory, chat and commands.
     */
    private void registerInteractionGuards() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) ->
                frozen(player.getUUID()) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
                frozen(player.getUUID()) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
                frozen(player.getUUID()) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
                frozen(player.getUUID()) ? InteractionResult.FAIL : InteractionResult.PASS);
    }

    private void registerJoinLeave() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(server, handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onDisconnect(handler.player));
    }

    /** Each server tick, kick any player who has been unauthenticated longer than the configured timeout. */
    private void registerKickTimeout() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (config.unauthenticatedKickSeconds() <= 0) {
                return;
            }
            long deadline = config.unauthenticatedKickSeconds() * 1000L;
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<UUID, Long>> it = joinedAt.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Long> entry = it.next();
                if (now - entry.getValue() < deadline) {
                    continue;
                }
                it.remove();
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null && !sessions.isAuthenticated(player.getUUID())) {
                    player.connection.disconnect(Messages.warn(Lang.of(player), "totpauth.kick.timeout"));
                }
            }
        });
    }

    private void onJoin(MinecraftServer server, ServerPlayer player) {
        // Carpet (and similar) fake players have no human behind them to ever
        // authenticate, so they bypass the gate completely.
        if (FakePlayers.isFake(player)) {
            sessions.authenticate(player.getUUID());
            return;
        }

        String rawName = player.nameAndId().name();
        String name = rawName.toLowerCase(Locale.ROOT);

        // Premium auto-login: ServerLoginPacketListenerImplMixin redirects this connection
        // through real Mojang verification when the claimed username matches a real account,
        // so a genuine online UUID here (rather than the offline-formula one) proves the
        // player just passed that handshake - no TOTP needed.
        if (config.premiumAutoLoginEnabled() && !player.getUUID().equals(OfflineUuid.of(rawName))) {
            sessions.authenticate(player.getUUID());
            player.sendSystemMessage(Messages.good(Lang.of(player), "totpauth.join.premium"));
            return;
        }

        Optional<UserRecord> record = store.get(name);

        // Trusted-IP grace: an ENROLLED player rejoining from the IP they last
        // authenticated with (within the trust window) skips the TOTP prompt and
        // is never frozen.
        if (record.isPresent() && record.get().state() == UserState.ENROLLED
                && TrustedIp.isValid(record.get(), PlayerIp.of(player), System.currentTimeMillis(),
                        config.trustedIpWindowMillis())) {
            sessions.authenticate(player.getUUID());
            player.sendSystemMessage(Messages.good(Lang.of(player), "totpauth.join.trusted_ip"));
            return;
        }

        // Otherwise every real join starts unauthenticated: freeze and prompt by state.
        sessions.deauthenticate(player.getUUID());
        limbo.send(player);
        joinedAt.put(player.getUUID(), System.currentTimeMillis());

        if (record.isEmpty()) {
            player.sendSystemMessage(Messages.warn(Lang.of(player), "totpauth.join.pending"));
            notifyOps(server, rawName);
        } else if (record.get().state() == UserState.PENDING) {
            Messages.sendSecret(player, totp, record.get().secret());
        } else {
            player.sendSystemMessage(Messages.warn(Lang.of(player), "totpauth.join.need_2fa"));
        }
    }

    private void onDisconnect(ServerPlayer player) {
        // A player who never authenticated is still in limbo: write their real
        // position back onto the entity before it is saved, so a disconnect can
        // never persist the limbo coordinates.
        if (!sessions.isAuthenticated(player.getUUID())) {
            limbo.restoreForSave(player);
        }
        sessions.deauthenticate(player.getUUID());
        joinedAt.remove(player.getUUID());
    }

    private void notifyOps(MinecraftServer server, String playerName) {
        LOGGER.info("[TotpAuth] {}", Lang.get(Lang.defaultLanguage(), "totpauth.ops.request", playerName, playerName));
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (server.getPlayerList().isOp(online.nameAndId())) {
                online.sendSystemMessage(
                        Messages.warn(Lang.of(online), "totpauth.ops.request", playerName, playerName));
            }
        }
    }

    private static boolean frozen(UUID uuid) {
        return !sessions.isAuthenticated(uuid);
    }
}
