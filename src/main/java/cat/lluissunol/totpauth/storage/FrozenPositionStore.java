package cat.lluissunol.totpauth.storage;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persists the real-world position a player held the moment they were sent to
 * the authentication "limbo", keyed by UUID.
 *
 * <p>This is the crash-safety net for the limbo teleport: if a player
 * disconnects (or the server dies) while still in limbo, their player data on
 * disk would otherwise record the limbo coordinates and strand them there. We
 * keep the genuine position here, restore it on a clean login, and self-heal on
 * the next join if the entry is still present.</p>
 *
 * <p>On-disk shape:</p>
 * <pre>
 * {
 *   "positions": {
 *     "&lt;uuid&gt;": { "x": 0.0, "y": 0.0, "z": 0.0, "yaw": 0.0, "pitch": 0.0 }
 *   }
 * }
 * </pre>
 */
public final class FrozenPositionStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;
    private final Map<String, StoredPosition> positions = new LinkedHashMap<>();

    public FrozenPositionStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** A captured player location: position plus look angles. */
    public record StoredPosition(double x, double y, double z, float yaw, float pitch) {
    }

    public synchronized void load() {
        positions.clear();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            PositionsFile parsed = GSON.fromJson(reader, PositionsFile.class);
            if (parsed != null && parsed.positions != null) {
                for (Map.Entry<String, StoredPosition> entry : parsed.positions.entrySet()) {
                    if (entry.getValue() != null) {
                        positions.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            if (!positions.isEmpty()) {
                logger.info("[TotpAuth] Restored {} pending limbo position(s) from {}.", positions.size(), file);
            }
        } catch (Exception e) {
            // A bad sidecar must never block boot; the worst case is a player needing a manual teleport.
            logger.warn("[TotpAuth] Could not read limbo positions from {}: {}", file, e.toString());
        }
    }

    public synchronized Optional<StoredPosition> get(UUID uuid) {
        return Optional.ofNullable(positions.get(uuid.toString()));
    }

    public synchronized boolean contains(UUID uuid) {
        return positions.containsKey(uuid.toString());
    }

    public synchronized void put(UUID uuid, StoredPosition position) {
        positions.put(uuid.toString(), position);
        save();
    }

    public synchronized void remove(UUID uuid) {
        if (positions.remove(uuid.toString()) != null) {
            save();
        }
    }

    private void save() {
        PositionsFile out = new PositionsFile();
        out.positions = new LinkedHashMap<>(positions);
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
            logger.error("[TotpAuth] Failed to write limbo positions to {}.", file, e);
        }
    }

    private void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException e) {
            logger.debug("[TotpAuth] Could not restrict permissions on {}: {}", path, e.toString());
        }
    }

    private static final class PositionsFile {
        Map<String, StoredPosition> positions;
    }
}
