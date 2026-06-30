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

Single-entrypoint server-side Fabric mod (`TotpAuthMod implements ModInitializer`). No client split — state lives in `<server>/config/totpauth/` (`config.json` for settings, `users.json` for player records, plus a transient `frozen_positions.json` safety net for the limbo teleport).

### Package layout

| Package | Responsibility |
|---|---|
| `auth` | `SessionManager` (in-memory, per-join UUID set), `UserRecord` (immutable value), `UserState` (PENDING / ENROLLED), `TrustedIp` (grace-window policy + constant) |
| `storage` | `UserStore` (users), `FrozenPositionStore` (limbo positions) — Gson-backed JSON, atomic write, POSIX-only `chmod 600` |
| `totp` | `TotpService` — wraps `java-otp` + `commons-codec` for HMAC-SHA1 TOTP (RFC 6238) |
| `command` | `TotpCommand` — registers `/2fa` with sub-commands `login`, `approve`, `reset`, `list` (`approve`/`reset` tab-complete player names) |
| `mixin` | `ServerGamePacketListenerImplMixin` — packet-boundary freeze for unauthenticated players; `ServerLoginPacketListenerImplMixin` — redirects `usesAuthentication()` to enable real Mojang verification for premium-named connections |
| `util` | `Limbo` (teleport-to-safety freeze), `PlayerFreezer` (blind/invisible effects), `FakePlayers` (Carpet-bot detection), `PlayerIp` (remote-IP extraction), `Lang` (server-side i18n), `AsciiQr` + `QrEncoder` (chat QR), `Messages`, `OfflineUuid`, `MojangAccounts` (cached Mojang username→account lookup) |
| `resources/assets/totpauth/lang` | Translation tables (`en_us`/`es_es`/`ca_es`.json); add a language by dropping a file and listing it in `Lang.BUNDLED` |

### Freeze: limbo + two packet/callback layers

Real unauthenticated players are handled in three complementary ways:

0. **Limbo** (`Limbo`, via `TotpAuthMod.onJoin`) — the player is teleported straight up to `LIMBO_Y` with `setNoGravity(true)` + `setInvulnerable(true)` (plus the blind/invisible effects from `PlayerFreezer`). This removes them from harm (creepers, mobs, fall/drown) and from client-side misprediction. Their real position is captured into `FrozenPositionStore` and restored by `Limbo.release` on login; `Limbo.restoreForSave` writes it back on a still-frozen disconnect so a crash can't strand them at the limbo coords.
1. **Mixin on `ServerGamePacketListenerImpl`** — cancels movement, inventory, chat, and command packets. Only `2fa login ...` commands pass through (`defaultRequire = 1` on all injectors, so a stale Mojmap name fails loudly at load).
2. **Fabric API interaction callbacks** (`AttackBlockCallback`, `UseBlockCallback`, `AttackEntityCallback`, `UseEntityCallback`) — registered in `TotpAuthMod`, return `InteractionResult.FAIL` when frozen.

`SessionManager` is held as a static on `TotpAuthMod` so the mixin can reach it without DI.

**Fake players bypass everything:** `FakePlayers.isFake` matches Carpet's `carpet.patches.EntityPlayerMPFake` by class name (walking the superclass chain, so Carpet stays an optional dependency). Such bots are authenticated immediately on join and never frozen.

### Key invariants

- **Messages are localized server-side** (`Lang`): vanilla clients have no lang files for our keys, so `Component.translatable` can't be used. The server loads bundled JSON tables and resolves each message in the *recipient's* client language (`ServerPlayer.clientInformation().language()`), sending plain translated text. Falls back exact → language-family (`es_mx`→`es_*`) → `en_us` → raw key. The console uses `en_us`. `en_us` must define every key (verified: 29 keys, all present in every table).
- **Player names are always lowercased** (`Locale.ROOT`) before storage and lookup.
- **UUIDs are offline UUIDs** — `UUID.nameUUIDFromBytes("OfflinePlayer:<name>")` — not Mojang account UUIDs.
- **Session auth is purely in-memory**: `SessionManager` is cleared on disconnect and on every join. Re-auth is required on every join *except* the trusted-IP grace window (below), which is the only thing that can re-populate the session without a code.
- **Trusted-IP grace window** (`TrustedIp`, default `WINDOW_MILLIS = 7 days`): on a successful `/2fa login`, the player's remote IP (`PlayerIp.of`, from `ServerGamePacketListenerImpl.getRemoteAddress()`, host only — no port) is written to `UserRecord.trustedIp/trustedIpAt`. In `onJoin`, an `ENROLLED` player whose join IP matches and is within the window is auto-authenticated and **never sent to limbo**. The window is anchored to the last real login (auto-logins do **not** slide it), so a code is entered at most once per window per IP. Only applies to `ENROLLED`; `/2fa reset` and re-`approve` clear it.
- **`UserStore.save()` is called synchronously on every mutation** and uses atomic move (fallback to non-atomic on Windows).
- **Gson is not bundled** — it is assumed present on the Minecraft server classpath. `java-otp`, `commons-codec` and `com.google.zxing:core` are Jar-in-Jar'd.
- **Fake players (Carpet bots) are never gated** — detected by class name and auto-authenticated on join, so all three freeze layers treat them as logged in.
- **The limbo teleport must round-trip the real position** — captured on freeze, restored on login and on disconnect. On a crash-recovery join `FrozenPositionStore.get` is authoritative over the player's (limbo) live position, so the capture is skipped when an entry already exists.
- **Premium auto-login** (`TotpConfig.premiumAutoLoginEnabled`, default `false`): `ServerLoginPacketListenerImplMixin` `@Redirect`s the single `MinecraftServer.usesAuthentication()` call inside `handleHello` so it also returns `true`, per-connection, when the claimed username resolves to a real Mojang account (`MojangAccounts.exists`, a short-timeout/cached lookup against Mojang's public profile API). This makes vanilla's own unmodified RSA/AES handshake + session-server check run for that connection — no crypto is reimplemented. `TotpAuthMod.onJoin` detects a successfully-verified connection by comparing `player.getUUID()` against `OfflineUuid.of(rawName)` (the *raw*, non-lowercased name — the offline-UUID formula is case-sensitive): a mismatch means the connection went through real verification, so the player is auto-authenticated and skips TOTP entirely. A claimed-but-unverified premium username is **hard-disconnected** by vanilla itself ("Failed to verify username!"), not gracefully downgraded to TOTP — this is intentional, otherwise anyone could register a TOTP account under a real Mojang account's name.

### Known TODOs in the code

`ServerGamePacketListenerImplMixin` has a commented-out injector for `ServerboundChatCommandSignedPacket` (MC 1.20.5+ signed command packets). The exact Mojmap handler name must be verified with `./gradlew genSources` before enabling it. The impact while frozen is minor — no signed command is `/2fa login`.

The 1.21.11 sources were verified via `genSources`: `MobEffects.BLINDNESS/INVISIBILITY` + the 6-arg `MobEffectInstance` constructor are valid, and several chat/resource APIs changed this drop — `ResourceLocation` → `Identifier`, `Style.withFont` now takes a `FontDescription.Resource(Identifier)`, and `ClickEvent`/`HoverEvent` are sealed records (`ClickEvent.CopyToClipboard`, `ClickEvent.SuggestCommand`, `HoverEvent.ShowText`). All are handled in `AsciiQr`/`Messages`. Same-dimension limbo teleports use `player.connection.teleport(x, y, z, yaw, pitch)`.
