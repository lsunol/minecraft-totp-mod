package cat.lluissunol.totpauth.command;

import cat.lluissunol.totpauth.auth.SessionManager;
import cat.lluissunol.totpauth.auth.UserRecord;
import cat.lluissunol.totpauth.auth.UserState;
import cat.lluissunol.totpauth.storage.UserStore;
import cat.lluissunol.totpauth.totp.TotpService;
import cat.lluissunol.totpauth.util.Limbo;
import cat.lluissunol.totpauth.util.Messages;
import cat.lluissunol.totpauth.util.OfflineUuid;
import cat.lluissunol.totpauth.util.PlayerIp;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The {@code /2fa} Brigadier command tree.
 *
 * <ul>
 *   <li>{@code /2fa login <code>}    - permission 0 (anyone): submit a TOTP code</li>
 *   <li>{@code /2fa approve <player>}- permission 2 (op): issue a secret, set PENDING</li>
 *   <li>{@code /2fa reset <player>}  - permission 2 (op): delete a record</li>
 *   <li>{@code /2fa list}            - permission 2 (op): list enrolled / pending players</li>
 * </ul>
 *
 * The console has permission level 4, so it always passes the op checks: it is
 * the recovery path and can never be locked out.
 */
public final class TotpCommand {

    private final UserStore store;
    private final SessionManager sessions;
    private final TotpService totp;
    private final Limbo limbo;

    public TotpCommand(UserStore store, SessionManager sessions, TotpService totp, Limbo limbo) {
        this.store = store;
        this.sessions = sessions;
        this.totp = totp;
        this.limbo = limbo;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("2fa")
                .then(Commands.literal("login")
                        .then(Commands.argument("code", StringArgumentType.word())
                                .executes(ctx -> login(ctx, StringArgumentType.getString(ctx, "code")))))
                .then(Commands.literal("approve")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    // Suggest online players who are not yet ENROLLED.
                                    ctx.getSource().getServer().getPlayerList().getPlayers().stream()
                                            .map(p -> p.nameAndId().name())
                                            .filter(n -> store.get(n.toLowerCase(Locale.ROOT))
                                                    .map(r -> r.state() != UserState.ENROLLED)
                                                    .orElse(true))
                                            .forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> approve(ctx, StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("reset")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    // Suggest all registered players (ENROLLED + PENDING).
                                    store.all().forEach(r -> builder.suggest(r.name()));
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> reset(ctx, StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("list")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(this::list)));
    }

    private int login(CommandContext<CommandSourceStack> ctx, String code) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Messages.error("Only in-game players can authenticate."));
            return 0;
        }

        String name = player.nameAndId().name().toLowerCase(Locale.ROOT);
        Optional<UserRecord> maybe = store.get(name);
        if (maybe.isEmpty()) {
            src.sendFailure(Messages.error("You are not registered yet. Ask an admin to approve you first."));
            return 0;
        }
        if (sessions.isAuthenticated(player.getUUID())) {
            src.sendSuccess(() -> Messages.info("You are already authenticated this session."), false);
            return Command.SINGLE_SUCCESS;
        }

        UserRecord record = maybe.get();
        if (!totp.verify(record.secret(), code)) {
            src.sendFailure(Messages.error("Invalid code. Check your authenticator app and try again."));
            return 0;
        }

        long now = System.currentTimeMillis();
        UserRecord updated = record;
        if (record.state() == UserState.PENDING) {
            updated = updated.withState(UserState.ENROLLED, now);
            player.sendSystemMessage(Messages.good("Enrollment confirmed - you're in. Welcome!"));
        } else {
            player.sendSystemMessage(Messages.good("Authenticated. Welcome back!"));
        }
        // Remember this IP so rejoining from it skips the prompt for the trust window.
        String ip = PlayerIp.of(player);
        if (ip != null) {
            updated = updated.withTrustedIp(ip, now);
        }
        if (updated != record) {
            store.put(updated);
        }
        sessions.authenticate(player.getUUID());
        limbo.release(player);
        return Command.SINGLE_SUCCESS;
    }

    private int approve(CommandContext<CommandSourceStack> ctx, String rawName) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        String name = rawName.toLowerCase(Locale.ROOT);

        Optional<UserRecord> existing = store.get(name);
        if (existing.isPresent() && existing.get().state() == UserState.ENROLLED) {
            src.sendFailure(Messages.error(rawName + " is already enrolled. Run /2fa reset "
                    + rawName + " first if you want to re-issue a secret."));
            return 0;
        }

        String secret = totp.generateSecret();
        store.put(new UserRecord(name, OfflineUuid.of(rawName), secret, UserState.PENDING,
                System.currentTimeMillis(), null, null, null));

        ServerPlayer online = server.getPlayerList().getPlayerByName(rawName);
        if (online != null) {
            Messages.sendSecret(online, totp, secret);
            src.sendSuccess(() -> Messages.good("Approved " + rawName
                    + ". Their secret was sent to them privately."), true);
        } else {
            src.sendSuccess(() -> Messages.good("Approved " + rawName
                    + ". Their secret will be shown the next time they join."), true);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reset(CommandContext<CommandSourceStack> ctx, String rawName) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();

        if (!store.remove(rawName)) {
            src.sendFailure(Messages.error("No record found for " + rawName + "."));
            return 0;
        }

        ServerPlayer online = server.getPlayerList().getPlayerByName(rawName);
        if (online != null) {
            sessions.deauthenticate(online.getUUID());
            limbo.send(online);
            online.sendSystemMessage(Messages.warn(
                    "Your 2FA registration was reset by an admin. You'll need to be approved again."));
        }
        src.sendSuccess(() -> Messages.good("Reset " + rawName + " - they are now unregistered."), true);
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();

        List<String> enrolled = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (UserRecord record : store.all()) {
            if (record.state() == UserState.ENROLLED) {
                enrolled.add(record.name());
            } else if (record.state() == UserState.PENDING) {
                pending.add(record.name());
            }
        }
        enrolled.sort(String::compareTo);
        pending.sort(String::compareTo);

        src.sendSuccess(() -> Messages.good("Enrolled (" + enrolled.size() + "): "
                + (enrolled.isEmpty() ? "(none)" : String.join(", ", enrolled))), false);
        src.sendSuccess(() -> Messages.warn("Pending (" + pending.size() + "): "
                + (pending.isEmpty() ? "(none)" : String.join(", ", pending))), false);
        return Command.SINGLE_SUCCESS;
    }
}
