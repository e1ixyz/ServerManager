# ServerManager — Handoff

Velocity proxy plugin that keeps backend Minecraft servers asleep until a player needs
them: starts Paper/Spigot backends on demand, keeps players online while they boot,
auto-sends once ping-ready, and stops idle backends. Also does network whitelisting
(with an embedded web form), moderation, join preferences, and a 3-state MOTD.

_Last updated 2026-06-29._

## What it is

A single Velocity 3.4.0 plugin (`dev.e1ixyz.servermanager`). It spawns/stops backend
server OS processes (Paper/Spigot launched via their own start scripts), tracks each
backend's lifecycle, and routes players to backends as they come up. macOS-only extras
let it open a Terminal window per backend for live logs + stdin forwarding. The
user-facing config, command, whitelist, and moderation behavior is documented in full
in `README.md` — this file is the maintainer's-eye snapshot.

## Run it

```bash
mvn clean package          # -> target/servermanager-0.1.0.jar (shaded)
```

- Build is the only gate; there is **no test harness** in the repo.
- Requires a JDK to build (Maven here runs on Java 25; `release` target is **17**).
- Velocity 3.4.0 provides MiniMessage/Adventure at runtime — only SnakeYAML is shaded
  (relocated to `dev.e1ixyz.servermanager.lib.snakeyaml`).
- Drop the jar in Velocity `plugins/`; first run writes `plugins/servermanager/config.yml`.

### Verify a build
```bash
unzip -l target/servermanager-0.1.0.jar | grep -i minimessage   # expect 0 (provided, not shaded)
unzip -l target/servermanager-0.1.0.jar | grep -c snakeyaml      # expect >0 (relocated, shaded)
```
Healthy jar is ~518KB. If it balloons to ~1.1MB, MiniMessage got shaded again — check
that the `adventure-text-minimessage` dependency in `pom.xml` is still `provided`.

## Architecture & file map

`src/main/java/dev/e1ixyz/servermanager/`
- `ServerManagerPlugin.java` — entry point: DI wiring, config load/migrate, command +
  event registration, shutdown teardown.
- `ServerProcessManager.java` — registry of `ManagedServer`s + hold-timer state; most
  methods `synchronized`. `servers` is a `ConcurrentHashMap` (read by unsynchronized
  `isRunning`/`isAnyRunning`/`isKnown` while `reload()` mutates it).
- `ManagedServer.java` — per-backend process control: start/stop, stdin command spool,
  log redirection, and the macOS Terminal/AppleScript console-window plumbing.
- `Config.java` / `ServerConfig.java` — YAML config model (SnakeYAML).
- `listeners/PlayerEvents.java` — the big one (~1700 lines): MOTD ping handling, login
  gate, initial-server choice, start queues + auto-send pollers, reconnect-window
  memory, hold auto-restart, proxy-empty stop-all, ServerBridge compat hooks.
- `commands/` — `ServerManagerCmd` (the `/sm` root, ~1500 lines), `Start/Stop/StatusCmd`,
  `ModerationCommands`.
- `moderation/` — `ModerationService` (ban/mute registry, login enforcement),
  `AutoIpBanService` (rate-based IP bans).
- `whitelist/` — `WhitelistService`, `WhitelistHttpServer` (embedded `com.sun.net`
  HTTP form), `VanillaWhitelistChecker` (reads backend `whitelist.json`).
- `preferences/JoinPreferenceService.java` — per-player preferred first-join backend.

Control flow: events in `PlayerEvents` drive `ServerProcessManager` →
`ManagedServer` for process actions; readiness is gated on Velocity `RegisteredServer.ping()`.

## Current state

- Builds clean against velocity-api 3.4.0 (latest *stable*; 3.5.0/4.0.0 are SNAPSHOT-only).
- No deprecated Velocity/Adventure API usage — fully on the modern `SimpleCommand` +
  `@Subscribe` + `Component.text`/`MiniMessage.miniMessage()` surface.
- Feature-complete per the README; actively iterated (recent commits were terminal-close
  and join-preference fixes).

## Gotchas

- **MiniMessage must stay `provided`** in `pom.xml`. Velocity bundles Adventure 4.26.1;
  shading an older MiniMessage caused version skew (linkage risk) + a ~1MB jar.
- **macOS-only console windows.** The AppleScript/Terminal path (`ManagedServer`) is
  macOS-only and has been a repeated source of "terminal won't close" races (see git
  log). Close logic kills the Terminal session by tty, not by tab.
- **Server names flow into AppleScript** (config-controlled, admin-trusted). Current
  escaping covers quotes/backslashes but not newlines — fine for admin input; harden if
  names ever become untrusted.
- **No tests.** Behavior changes are validated by build + manual proxy runs only.
- `PlayerEvents.shutdown()` already cancels `pendingServerStop`, `pendingConnect`,
  `holdRestartTasks`, and the heartbeat — don't re-add that.

## Recent change (2026-06-29 session)

Velocity-currency + bug sweep:
- `pom.xml`: MiniMessage `4.17.0 → 4.26.1`, scope `provided` (match Velocity's bundled
  Adventure; stop shading it). Jar 1.14MB → 518KB.
- `ManagedServer.isRunning()`: snapshot the volatile `process` before null-check + `isAlive()`
  (was a null-race with `stopGracefully()`).
- `ManagedServer` AppleScript helpers (`runAppleScript`, `listTerminalProcessIds`):
  destroy the spawned `Process` in a `finally` (were leaking on throw; run every start/stop).
- `ServerProcessManager.servers`: `HashMap → ConcurrentHashMap` (unsynchronized reads
  raced with `reload()`).

## Where to look first

`PlayerEvents.java` is where most behavior lives — start there. For process/lifecycle
questions, `ManagedServer` + `ServerProcessManager`. For config shape, `Config.java`.
