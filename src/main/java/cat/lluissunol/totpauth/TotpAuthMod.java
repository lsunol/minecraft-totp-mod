package cat.lluissunol.totpauth;

import cat.lluissunol.totpauth.auth.SessionManager;
import cat.lluissunol.totpauth.auth.UserRecord;
import cat.lluissunol.totpauth.auth.UserState;
import cat.lluissunol.totpauth.command.TotpCommand;
import cat.lluissunol.totpauth.storage.UserStore;
import cat.lluissunol.totpauth.totp.TotpService;
import cat.lluissunol.totpauth.util.Messages;
import cat.lluissunol.totpauth.util.PlayerFreezer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side TOTP authentication gate.
 *
 * <p>On an offline-mode server every player is frozen on join until they submit
 * a valid TOTP code via {@code /2fa login}. New players must be approved by an
 * admin (or the console) with {@code /2fa approve}.</p>
 */
public final class TotpAuthMod implements ModInitializer {

    public static final String MOD_ID = "totpauth";
    public static final Logger LOGGER = LoggerFactory.getLogger("TotpAuth");

    /** Held statically so the packet-handler mixin can query session state. */
    private static SessionManager sessions;

    private UserStore store;
    private TotpService totp;

    @Override
    public void onInitialize() {
        sessions = new SessionManager();
        totp = new TotpService();

        Path file = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID).resolve("users.json");
        store = new UserStore(file, LOGGER);
        store.load();

        registerCommands();
        registerInteractionGuards();
        registerJoinLeave();

        LOGGER.info("[TotpAuth] Initialised. User store: {}", file);
    }

    public static SessionManager sessions() {
        return sessions;
    }

    private void registerCommands() {
        TotpCommand command = new TotpCommand(store, sessions, totp);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                command.register(dispatcher));
    }

    /**
     * "Fabric API callbacks where cleaner": block interactions for frozen players.
     * Returning {@link InteractionResult#FAIL} cancels the interaction; {@code PASS}
     * lets vanilla handle it normally. The packet-handler mixin covers movement,
     * inventory, chat and commands.
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
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                sessions.deauthenticate(handler.player.getUUID()));
    }

    private void onJoin(MinecraftServer server, ServerPlayer player) {
        // Every join starts unauthenticated, then we freeze and prompt according to state.
        sessions.deauthenticate(player.getUUID());
        PlayerFreezer.freeze(player);

        String name = player.nameAndId().name().toLowerCase(Locale.ROOT);
        Optional<UserRecord> record = store.get(name);

        if (record.isEmpty()) {
            player.sendSystemMessage(Messages.warn(
                    "Access is restricted - you are pending admin approval. Please wait."));
            notifyOps(server, player.nameAndId().name());
        } else if (record.get().state() == UserState.PENDING) {
            Messages.sendSecret(player, totp, record.get().secret());
        } else {
            player.sendSystemMessage(Messages.warn(
                    "This server requires 2FA. Authenticate with /2fa login <code>."));
        }
    }

    private void notifyOps(MinecraftServer server, String playerName) {
        String request = "Player " + playerName + " requests access. Use /2fa approve " + playerName;
        LOGGER.info("[TotpAuth] {}", request);
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (server.getPlayerList().isOp(online.nameAndId())) {
                online.sendSystemMessage(Messages.warn(request));
            }
        }
    }

    private static boolean frozen(UUID uuid) {
        return !sessions.isAuthenticated(uuid);
    }
}
