package cat.lluissunol.totpauth.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Derives the offline-mode UUID for a player name the same way a vanilla
 * offline server does: {@code UUID.nameUUIDFromBytes("OfflinePlayer:<name>")}.
 */
public final class OfflineUuid {

    private OfflineUuid() {
    }

    public static UUID of(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
