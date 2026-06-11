package cat.lluissunol.totpauth.storage;

import cat.lluissunol.totpauth.auth.UserRecord;
import cat.lluissunol.totpauth.auth.UserState;
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
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Gson-backed JSON store for user records.
 *
 * <p>Loaded once on server start, written on every mutation. Secrets are stored
 * in cleartext, so the file is created with owner-only permissions where the
 * filesystem supports it (see README for the security note).</p>
 *
 * <p>On-disk shape:</p>
 * <pre>
 * {
 *   "users": {
 *     "&lt;lowercase_name&gt;": {
 *       "uuid": "...", "secret": "BASE32", "status": "PENDING|ENROLLED",
 *       "createdAt": 0, "confirmedAt": 0, "trustedIp": "1.2.3.4", "trustedIpAt": 0
 *     }
 *   }
 * }
 * </pre>
 */
public final class UserStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private final Path file;
    private final Logger logger;
    private final Map<String, UserRecord> users = new LinkedHashMap<>();

    public UserStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public synchronized void load() {
        users.clear();
        if (!Files.exists(file)) {
            logger.info("[TotpAuth] No users file at {} - starting with an empty store.", file);
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            UsersFile parsed = GSON.fromJson(reader, UsersFile.class);
            if (parsed != null && parsed.users != null) {
                for (Map.Entry<String, StoredUser> entry : parsed.users.entrySet()) {
                    StoredUser stored = entry.getValue();
                    if (stored == null || stored.secret == null || stored.status == null) {
                        continue;
                    }
                    String name = entry.getKey().toLowerCase(Locale.ROOT);
                    UUID uuid = stored.uuid != null ? UUID.fromString(stored.uuid) : null;
                    users.put(name, new UserRecord(name, uuid, stored.secret,
                            parseState(stored.status), stored.createdAt, stored.confirmedAt,
                            stored.trustedIp, stored.trustedIpAt));
                }
            }
            logger.info("[TotpAuth] Loaded {} user record(s) from {}.", users.size(), file);
        } catch (Exception e) {
            // Refuse to boot on a corrupt store rather than silently dropping everyone's records.
            throw new IllegalStateException("Could not load TotpAuth user store at " + file, e);
        }
    }

    public synchronized void save() {
        UsersFile out = new UsersFile();
        out.users = new LinkedHashMap<>();
        for (UserRecord record : users.values()) {
            StoredUser stored = new StoredUser();
            stored.uuid = record.uuid() != null ? record.uuid().toString() : null;
            stored.secret = record.secret();
            stored.status = record.state().name();
            stored.createdAt = record.createdAt();
            stored.confirmedAt = record.confirmedAt();
            stored.trustedIp = record.trustedIp();
            stored.trustedIpAt = record.trustedIpAt();
            out.users.put(record.name(), stored);
        }

        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(out, writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(file);
        } catch (IOException e) {
            logger.error("[TotpAuth] Failed to write user store to {}.", file, e);
        }
    }

    public synchronized Optional<UserRecord> get(String name) {
        return Optional.ofNullable(users.get(name.toLowerCase(Locale.ROOT)));
    }

    public synchronized void put(UserRecord record) {
        users.put(record.name().toLowerCase(Locale.ROOT), record);
        save();
    }

    public synchronized boolean remove(String name) {
        boolean removed = users.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized Collection<UserRecord> all() {
        return List.copyOf(users.values());
    }

    private static UserState parseState(String raw) {
        try {
            return UserState.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UserState.PENDING;
        }
    }

    private void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX filesystem (e.g. Windows): cannot tighten perms here. See the README security note.
            logger.debug("[TotpAuth] Could not restrict permissions on {}: {}", path, e.toString());
        }
    }

    // ---- On-disk JSON shape (Gson DTOs) ----

    private static final class UsersFile {
        Map<String, StoredUser> users;
    }

    private static final class StoredUser {
        String uuid;
        String secret;
        String status;
        long createdAt;
        Long confirmedAt;
        String trustedIp;
        Long trustedIpAt;
    }
}
