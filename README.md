# TotpAuth

A **server-side-only** Fabric mod that gates entry to an **offline-mode** Minecraft
server behind a **TOTP** code (RFC 6238 — HMAC-SHA1, 6 digits, 30-second period).
It is compatible with Google Authenticator, Authy, FreeOTP and any other standard
authenticator app.

Players connect with a **vanilla client** — there is no client mod and no custom
GUI. Everything happens through chat messages and the `/2fa` command. An admin (or
the server console) approves each new player once; after that the player
authenticates with a 6-digit code **on every join**.

> ⚠️ This mod does not implement online-mode account security. It is meant for
> private offline-mode (cracked) servers where you want to control who gets in
> and stop name impersonation.

---

## Versions

| Component | Version |
|---|---|
| Minecraft | `1.21.11` |
| Java | `21` |
| Fabric Loader | `0.19.3` |
| Fabric API | `0.141.4+1.21.11` |
| Fabric Loom | `1.16.3` |
| Mappings | Official Mojang mappings (`loom.officialMojangMappings()`) |

Bundled into the jar via Fabric's Jar-in-Jar (you do **not** install these
separately):

- `com.eatthepath:java-otp:0.4.0` — TOTP generation/verification
- `commons-codec:commons-codec:1.18.0` — Base32 encode/decode of secrets

Gson is **not** bundled — it already ships on the Minecraft server classpath.

---

## Building

```bash
./gradlew build
```

> If your checkout is missing the Gradle wrapper jar (`gradle/wrapper/gradle-wrapper.jar`),
> generate the wrapper once with a local Gradle 9.x — `gradle wrapper --gradle-version 9.4.1` —
> or just build directly with a system Gradle 9.x via `gradle build`. (Loom 1.16.3 requires
> Gradle 9.x; Gradle 8.x is not supported.)

The finished mod jar is written to:

```
build/libs/totpauth-1.0.0.jar
```

Ignore the `-sources.jar` next to it — the plain `totpauth-1.0.0.jar` is the one
you deploy (it already contains the bundled libraries).

---

## Installing (Crafty / any Fabric server)

1. Make sure your server runs **Fabric Loader 0.19.3** for **Minecraft 1.21.11**.
2. Put **both** jars in the server's `mods/` folder:
   - `totpauth-1.0.0.jar`
   - `fabric-api-0.141.4+1.21.11.jar` (download from Modrinth/CurseForge)
3. Set the server to offline mode in `server.properties`:
   ```properties
   online-mode=false
   ```
4. Start (or restart) the server. On Crafty Controller, upload the jars to the
   server's `mods/` directory and restart from the panel.

On first start the mod logs:

```
[TotpAuth] Initialised. User store: .../config/totpauth/users.json
```

---

## Configuration / data

There is no hand-edited config file. State lives in a JSON store that is created
automatically and survives Docker/Crafty restarts:

```
<server>/config/totpauth/users.json
```

Shape:

```json
{
  "users": {
    "steve": {
      "uuid": "8667ba71-b85a-3604-af52-5c30a96fb87b",
      "secret": "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
      "status": "ENROLLED",
      "createdAt": 1749200000000,
      "confirmedAt": 1749200100000
    }
  }
}
```

- Keys are **lowercase** player names.
- `uuid` is the offline UUID: `UUID.nameUUIDFromBytes("OfflinePlayer:<name>")`.
- `status` is `PENDING` (secret issued, not yet confirmed) or `ENROLLED`.

### 🔐 Security note

**Secrets are stored in cleartext.** Anyone who can read `users.json` can generate
valid codes. The mod sets the file to owner-only (`600`) permissions on POSIX
filesystems, but you should still:

- restrict access to the server's `config/` directory,
- keep backups of `users.json` private,
- avoid committing it anywhere.

---

## Workflow

### How a player gets in (happy path)

1. **Player joins** for the first time. They are frozen (blind, invisible, can't
   move/interact/chat) and told they are pending approval. Every online op sees:
   `Player <name> requests access. Use /2fa approve <name>`.
2. **An op approves them:** `/2fa approve <name>`. The mod generates a secret and
   privately shows the player the Base32 secret + an `otpauth://` link + setup
   instructions. (If the player is offline, the secret is shown on their next
   join while they remain `PENDING`.)
3. **Player adds the secret** to their authenticator app (TOTP, SHA1, 6 digits,
   30s) and runs `/2fa login <code>`. The first valid code confirms enrollment
   (`PENDING → ENROLLED`) and unfreezes them.
4. **On every later join** the player is frozen again and simply runs
   `/2fa login <code>` to play. Authentication lasts for that session only.

### Commands

| Command | Permission | Effect |
|---|---|---|
| `/2fa login <code>` | everyone (0) | Submit a TOTP code. Confirms enrollment if `PENDING`, otherwise authenticates for this session. |
| `/2fa approve <player>` | op (2) | Generate a secret, set the player `PENDING`, and reveal the secret to them privately. |
| `/2fa reset <player>` | op (2) | Delete the player's record → back to unregistered. |
| `/2fa list` | op (2) | List `ENROLLED` players, with `PENDING` players in a separate section. |

### Bootstrapping the first admin (recovery path)

The **server console is never frozen and always has full permissions**, so it is
the bootstrap and recovery path:

- First op joining an empty server? They start unregistered and frozen. From the
  **server console / Crafty console** run:
  ```
  2fa approve <your_name>
  ```
  Then, in game, the (still-frozen) player runs `/2fa login <code>` to enroll.
- Locked out / lost your authenticator? From the console run
  `2fa reset <name>` and then `2fa approve <name>` to issue a fresh secret.

You can never permanently lock yourself out as long as you have console access.

---

## How the freeze works

For any non-authenticated player:

- **Movement, inventory/container clicks, chat, and commands** are cancelled at
  the packet boundary by a Mixin on `ServerGamePacketListenerImpl`
  (`handleMovePlayer`, `handleContainerClick`, `handlePlayerAction`,
  `handleUseItemOn`, `handleUseItem`, `handleInteract`, `handleChat`,
  `handleChatCommand`). Only `/2fa login …` is allowed through.
- **Block break/use, item use, and entity attack/interact** are additionally
  blocked via Fabric API interaction callbacks (`AttackBlockCallback`,
  `UseBlockCallback`, `AttackEntityCallback`, `UseEntityCallback`).
- **Blindness + Invisibility** are applied so the world can't be scouted while
  waiting at the login prompt.

Authentication is per-session and in-memory only — re-auth is required on every
join (no IP grace window, by design).

### Version-specific TODOs

Most Mojmap names used here are stable across 1.20.x–1.21.x, but two spots are
deliberately flagged to **verify against the decompiled 1.21.11 sources**
(`./gradlew genSources`) rather than guessed:

1. **Signed command packet** (`ServerGamePacketListenerImplMixin`): MC 1.20.5+
   splits command input into `ServerboundChatCommandPacket` (handled) and
   `ServerboundChatCommandSignedPacket` (commands with signed chat arguments,
   e.g. `/msg`). The exact Mojmap handler name for the signed variant
   (`handleSignedChatCommand` vs `handleChatCommandSigned`) is left as a
   clearly-marked TODO with a ready-to-enable `@Inject`. The normal
   `/2fa login <code>` path is **unsigned** and is fully handled.
2. **Status effects** (`PlayerFreezer`): the `MobEffects.BLINDNESS/INVISIBILITY`
   holder constants and the 6-arg `MobEffectInstance` constructor are flagged to
   double-check, though they have been stable through 1.21.x.

Each injected mixin method uses `defaultRequire = 1`, so if any targeted name is
stale the server fails fast at load with a clear error instead of silently
leaving a hole in the freeze.

---

## License

MIT — see [`LICENSE`](LICENSE).
