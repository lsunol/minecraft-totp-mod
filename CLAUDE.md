# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

```bash
./gradlew build
```

Output: `build/libs/totpauth-1.0.0.jar` (Jar-in-Jar; this is the deployable artifact).

To regenerate decompiled Minecraft sources for Mojmap name verification:

```bash
./gradlew genSources
```

There are no tests in this project. Build success is the only automated verification.

**Gradle requirement:** Loom 1.16.3 requires Gradle 9.x. If the wrapper jar is missing, run `gradle wrapper --gradle-version 9.4.1` first.

## Architecture

Single-entrypoint server-side Fabric mod (`TotpAuthMod implements ModInitializer`). No client split, no config file — state lives in `<server>/config/totpauth/users.json`.

### Package layout

| Package | Responsibility |
|---|---|
| `auth` | `SessionManager` (in-memory, per-join UUID set), `UserRecord` (immutable value), `UserState` (PENDING / ENROLLED) |
| `storage` | `UserStore` — Gson-backed JSON, atomic write, POSIX-only `chmod 600` |
| `totp` | `TotpService` — wraps `java-otp` + `commons-codec` for HMAC-SHA1 TOTP (RFC 6238) |
| `command` | `TotpCommand` — registers `/2fa` with sub-commands `login`, `approve`, `reset`, `list` |
| `mixin` | `ServerGamePacketListenerImplMixin` — packet-boundary freeze for unauthenticated players |
| `util` | `Messages`, `PlayerFreezer`, `OfflineUuid` |

### Two-layer freeze

Unauthenticated players are blocked at two independent layers:

1. **Mixin on `ServerGamePacketListenerImpl`** — cancels movement, inventory, chat, and command packets. Only `2fa login ...` commands pass through (`defaultRequire = 1` on all injectors, so a stale Mojmap name fails loudly at load).
2. **Fabric API interaction callbacks** (`AttackBlockCallback`, `UseBlockCallback`, `AttackEntityCallback`, `UseEntityCallback`) — registered in `TotpAuthMod`, return `InteractionResult.FAIL` when frozen.

`SessionManager` is held as a static on `TotpAuthMod` so the mixin can reach it without DI.

### Key invariants

- **Player names are always lowercased** (`Locale.ROOT`) before storage and lookup.
- **UUIDs are offline UUIDs** — `UUID.nameUUIDFromBytes("OfflinePlayer:<name>")` — not Mojang account UUIDs.
- **Session auth is purely in-memory**: `SessionManager` is cleared on disconnect and on every join, so re-auth is always required.
- **`UserStore.save()` is called synchronously on every mutation** and uses atomic move (fallback to non-atomic on Windows).
- **Gson is not bundled** — it is assumed present on the Minecraft server classpath. Only `java-otp` and `commons-codec` are Jar-in-Jar'd.

### Known TODOs in the code

`ServerGamePacketListenerImplMixin` has a commented-out injector for `ServerboundChatCommandSignedPacket` (MC 1.20.5+ signed command packets). The exact Mojmap handler name must be verified with `./gradlew genSources` before enabling it. The impact while frozen is minor — no signed command is `/2fa login`.

`PlayerFreezer` has a note to double-check `MobEffects.BLINDNESS/INVISIBILITY` holder constants and the `MobEffectInstance` constructor against 1.21.11 decompiled sources.
