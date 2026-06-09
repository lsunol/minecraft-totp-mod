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

Single-entrypoint server-side Fabric mod (`TotpAuthMod implements ModInitializer`). No client split, no hand-edited config — state lives in `<server>/config/totpauth/` (`users.json`, plus a transient `frozen_positions.json` safety net for the limbo teleport).

### Package layout

| Package | Responsibility |
|---|---|
| `auth` | `SessionManager` (in-memory, per-join UUID set), `UserRecord` (immutable value), `UserState` (PENDING / ENROLLED) |
| `storage` | `UserStore` (users), `FrozenPositionStore` (limbo positions) — Gson-backed JSON, atomic write, POSIX-only `chmod 600` |
| `totp` | `TotpService` — wraps `java-otp` + `commons-codec` for HMAC-SHA1 TOTP (RFC 6238) |
| `command` | `TotpCommand` — registers `/2fa` with sub-commands `login`, `approve`, `reset`, `list` |
| `mixin` | `ServerGamePacketListenerImplMixin` — packet-boundary freeze for unauthenticated players |
| `util` | `Limbo` (teleport-to-safety freeze), `PlayerFreezer` (blind/invisible effects), `FakePlayers` (Carpet-bot detection), `AsciiQr` + `QrEncoder` (chat QR), `Messages`, `OfflineUuid` |

### Freeze: limbo + two packet/callback layers

Real unauthenticated players are handled in three complementary ways:

0. **Limbo** (`Limbo`, via `TotpAuthMod.onJoin`) — the player is teleported straight up to `LIMBO_Y` with `setNoGravity(true)` + `setInvulnerable(true)` (plus the blind/invisible effects from `PlayerFreezer`). This removes them from harm (creepers, mobs, fall/drown) and from client-side misprediction. Their real position is captured into `FrozenPositionStore` and restored by `Limbo.release` on login; `Limbo.restoreForSave` writes it back on a still-frozen disconnect so a crash can't strand them at the limbo coords.
1. **Mixin on `ServerGamePacketListenerImpl`** — cancels movement, inventory, chat, and command packets. Only `2fa login ...` commands pass through (`defaultRequire = 1` on all injectors, so a stale Mojmap name fails loudly at load).
2. **Fabric API interaction callbacks** (`AttackBlockCallback`, `UseBlockCallback`, `AttackEntityCallback`, `UseEntityCallback`) — registered in `TotpAuthMod`, return `InteractionResult.FAIL` when frozen.

`SessionManager` is held as a static on `TotpAuthMod` so the mixin can reach it without DI.

**Fake players bypass everything:** `FakePlayers.isFake` matches Carpet's `carpet.patches.EntityPlayerMPFake` by class name (walking the superclass chain, so Carpet stays an optional dependency). Such bots are authenticated immediately on join and never frozen.

### Key invariants

- **Player names are always lowercased** (`Locale.ROOT`) before storage and lookup.
- **UUIDs are offline UUIDs** — `UUID.nameUUIDFromBytes("OfflinePlayer:<name>")` — not Mojang account UUIDs.
- **Session auth is purely in-memory**: `SessionManager` is cleared on disconnect and on every join, so re-auth is always required.
- **`UserStore.save()` is called synchronously on every mutation** and uses atomic move (fallback to non-atomic on Windows).
- **Gson is not bundled** — it is assumed present on the Minecraft server classpath. `java-otp`, `commons-codec` and `com.google.zxing:core` are Jar-in-Jar'd.
- **Fake players (Carpet bots) are never gated** — detected by class name and auto-authenticated on join, so all three freeze layers treat them as logged in.
- **The limbo teleport must round-trip the real position** — captured on freeze, restored on login and on disconnect. On a crash-recovery join `FrozenPositionStore.get` is authoritative over the player's (limbo) live position, so the capture is skipped when an entry already exists.

### Known TODOs in the code

`ServerGamePacketListenerImplMixin` has a commented-out injector for `ServerboundChatCommandSignedPacket` (MC 1.20.5+ signed command packets). The exact Mojmap handler name must be verified with `./gradlew genSources` before enabling it. The impact while frozen is minor — no signed command is `/2fa login`.

The 1.21.11 sources were verified via `genSources`: `MobEffects.BLINDNESS/INVISIBILITY` + the 6-arg `MobEffectInstance` constructor are valid, and several chat/resource APIs changed this drop — `ResourceLocation` → `Identifier`, `Style.withFont` now takes a `FontDescription.Resource(Identifier)`, and `ClickEvent`/`HoverEvent` are sealed records (`ClickEvent.CopyToClipboard`, `ClickEvent.SuggestCommand`, `HoverEvent.ShowText`). All are handled in `AsciiQr`/`Messages`. Same-dimension limbo teleports use `player.connection.teleport(x, y, z, yaw, pitch)`.
