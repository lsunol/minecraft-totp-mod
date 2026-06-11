package cat.lluissunol.totpauth.util;

import net.minecraft.server.level.ServerPlayer;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Extracts a player's remote IP address as a stable string.
 *
 * <p>Only the host part is returned (no port), since the source port changes on
 * every connection and would defeat any "same IP" comparison. Reads the address
 * from {@code ServerGamePacketListenerImpl.getRemoteAddress()} (verified against
 * the 1.21.11 Mojmap sources).</p>
 */
public final class PlayerIp {

    private PlayerIp() {
    }

    /** The player's remote host address, or {@code null} if it cannot be determined. */
    public static String of(ServerPlayer player) {
        SocketAddress address = player.connection.getRemoteAddress();
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return address != null ? address.toString() : null;
    }
}
