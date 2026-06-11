package cat.lluissunol.totpauth.command;

import cat.lluissunol.totpauth.TotpAuthMod;
import cat.lluissunol.totpauth.auth.SessionManager;
import cat.lluissunol.totpauth.auth.UserRecord;
import cat.lluissunol.totpauth.auth.UserState;
import cat.lluissunol.totpauth.storage.UserStore;
import cat.lluissunol.totpauth.totp.TotpService;
import cat.lluissunol.totpauth.util.Lang;
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
import net.minecraft.network.chat.Component;
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
            src.sendFailure(Messages.error(Lang.of(src), "totpauth.login.players_only"));
            return 0;
        }
        String lang = Lang.of(player);

        String name = player.nameAndId().name().toLowerCase(Locale.ROOT);
        Optional<UserRecord> maybe = store.get(name);
        if (maybe.isEmpty()) {
            src.sendFailure(Messages.error(lang, "totpauth.login.not_registered"));
            return 0;
        }
        if (sessions.isAuthenticated(player.getUUID())) {
            src.sendSuccess(() -> Messages.info(lang, "totpauth.login.already_auth"), false);
            return Command.SINGLE_SUCCESS;
        }

        UserRecord record = maybe.get();
        if (!totp.verify(record.secret(), code)) {
            src.sendFailure(diagnoseFailure(record, code, name, lang));
            return 0;
        }

        long now = System.currentTimeMillis();
        UserRecord updated = record;
        if (record.state() == UserState.PENDING) {
            updated = updated.withState(UserState.ENROLLED, now);
            player.sendSystemMessage(Messages.good(lang, "totpauth.login.enrolled"));
        } else {
            player.sendSystemMessage(Messages.good(lang, "totpauth.login.welcome_back"));
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

    /**
     * Turn a rejected code into an actionable message. A code that only matches
     * with a large time skew means the server clock is out of sync (the usual
     * cause of "every code is rejected"); a code that matches nowhere means the
     * authenticator holds a different secret than the server.
     */
    private Component diagnoseFailure(UserRecord record, String code, String name, String lang) {
        Integer offset = totp.diagnoseOffsetSteps(record.secret(), code);
        if (offset == null) {
            TotpAuthMod.LOGGER.warn("[TotpAuth] Rejected code for {}: no match within +/-30 min - "
                    + "the authenticator secret differs from the stored one.", name);
            return Messages.error(lang, "totpauth.login.bad_code_secret");
        }
        long secs = Math.abs((long) offset) * totp.stepSeconds();
        String dir = offset > 0 ? "behind" : "ahead of";
        TotpAuthMod.LOGGER.warn("[TotpAuth] Rejected code for {}: only matches with a ~{}s skew "
                + "(server clock is {} the authenticator). Sync the server clock via NTP.", name, secs, dir);
        return Messages.error(lang, "totpauth.login.bad_code_clock", secs);
    }

    private int approve(CommandContext<CommandSourceStack> ctx, String rawName) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        String name = rawName.toLowerCase(Locale.ROOT);

        String lang = Lang.of(src);
        Optional<UserRecord> existing = store.get(name);
        if (existing.isPresent() && existing.get().state() == UserState.ENROLLED) {
            src.sendFailure(Messages.error(lang, "totpauth.approve.already_enrolled", rawName, rawName));
            return 0;
        }

        String secret = totp.generateSecret();
        store.put(new UserRecord(name, OfflineUuid.of(rawName), secret, UserState.PENDING,
                System.currentTimeMillis(), null, null, null));

        ServerPlayer online = server.getPlayerList().getPlayerByName(rawName);
        if (online != null) {
            Messages.sendSecret(online, totp, secret);
            src.sendSuccess(() -> Messages.good(lang, "totpauth.approve.sent", rawName), true);
        } else {
            src.sendSuccess(() -> Messages.good(lang, "totpauth.approve.on_join", rawName), true);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reset(CommandContext<CommandSourceStack> ctx, String rawName) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();

        String lang = Lang.of(src);
        if (!store.remove(rawName)) {
            src.sendFailure(Messages.error(lang, "totpauth.reset.not_found", rawName));
            return 0;
        }

        ServerPlayer online = server.getPlayerList().getPlayerByName(rawName);
        if (online != null) {
            sessions.deauthenticate(online.getUUID());
            limbo.send(online);
            online.sendSystemMessage(Messages.warn(Lang.of(online), "totpauth.reset.player_notice"));
        }
        src.sendSuccess(() -> Messages.good(lang, "totpauth.reset.done", rawName), true);
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

        String lang = Lang.of(src);
        String none = Lang.get(lang, "totpauth.list.none");
        String enrolledNames = enrolled.isEmpty() ? none : String.join(", ", enrolled);
        String pendingNames = pending.isEmpty() ? none : String.join(", ", pending);
        src.sendSuccess(() -> Messages.good(lang, "totpauth.list.enrolled", enrolled.size(), enrolledNames), false);
        src.sendSuccess(() -> Messages.warn(lang, "totpauth.list.pending", pending.size(), pendingNames), false);
        return Command.SINGLE_SUCCESS;
    }
}
