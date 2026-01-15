# ServerManager

ServerManager is a Velocity plugin that keeps your backend Minecraft servers asleep until a player needs them. It starts Paper/Spigot servers on-demand, keeps players informed while they boot, and shuts them down again when everything is idle. The goal is to run zero unused processes without forcing players to manage connections manually.

## Highlights
- Starts the designated primary backend automatically on the first join and redirects the player with a customizable kick message.
- Honors Velocity forced-host routing: when the proxy points a first-join at a non-primary backend, that server is started instead, with optional per-host MOTD and kick overrides.
- Intercepts `/server <name>` (or GUI menu joins) to launch offline managed servers, keeps the player online, queues the connection, and auto-sends once the ping succeeds.
- Three-state MOTD (offline/starting/online) driven by MiniMessage templates, including a distinct "starting" banner.
- Graceful per-server shutdowns when a backend empties, plus a safety stop-all when the entire proxy is empty with optional startup grace.
- Admin hold command (`/sm hold`) keeps a backend online for a set window even when it is empty.
- Optional per-server log files so backend stdout/stderr does not spam the Velocity console.
- Fully configurable player-facing messages, permissions-friendly management commands, and an optional network whitelist with self-serve web onboarding.
- Built-in moderation: `/ban`, `/ipban`, `/unban`, `/mute`, `/unmute`, `/warn`, plus `/banlist` and `/mutelist`. Banned players are blocked at login so they cannot start backends.
- In-game `/sm whitelist` tooling to view/add/remove entries from both the network whitelist and any managed vanilla `whitelist.json`.
- Optional daily auto-restart time for servers held indefinitely, with 1-minute and 5-second warnings broadcast in-game.

## Requirements
- Java 17+
- Velocity 3.4.0-SNAPSHOT-534 (or the API version you build against)
- Maven (for building from source)
- Paper/Spigot servers that can be launched via command line within their working directory

## Building
1. Compile and package:
   ```bash
   mvn clean package
   ```
   Maven is configured to pull the Velocity API snapshot (3.4.0-SNAPSHOT) from PaperMC/Velocity repos. If your specific build number is not available there, install the JAR manually with:
   ```bash
   mvn install:install-file \
     -Dfile=/path/to/velocity-3.4.0-SNAPSHOT.jar \
     -DgroupId=com.velocitypowered \
     -DartifactId=velocity-api \
     -Dversion=3.4.0-SNAPSHOT \
     -Dpackaging=jar
   ```
2. The shaded plugin JAR will be created at `target/servermanager-0.1.0.jar`.

## Installation
1. Copy the shaded JAR into your Velocity `plugins/` directory.
2. Start Velocity once; the plugin writes the default configuration to `plugins/servermanager/config.yml`.
3. Edit the configuration (see below) with your backend details.
4. Restart Velocity and verify the plugin logs: `ServerManager initialized. Primary: <server>`.

## Configuration
Default file snippets (generated on first run) are shown below. All MiniMessage strings support the standard placeholder set:
- `<server>` resolves to the backend name.
- `<player>` resolves to the player username (where applicable).
- `<state>` is available for status messages.
- `<duration>` is used by hold notifications to display the remaining pin time.

```yaml
kickMessage: "Server Starting"
startupGraceSeconds: 15
stopGraceSeconds: 60

motd:
  offline:  "<gray>Your Network</gray> <gray>- <white><server></white></gray>"
  offline2: "<red><bold>Server Offline - Join to Start</bold></red>"
  starting: "<yellow><bold>Server Starting</bold></yellow> <white><server></white>"
  starting2: "<gray>Please wait...</gray>"
  online:   "<gray>Your Network</gray> <gray>- <white><server></white></gray>"
  online2:  "<green><bold>Server Online</bold></green>"

messages:
  noPermission:   "<red>You don't have permission.</red>"
  usage:          "<gray>Usage:</gray> <white>/sm <green>start</green>|<green>stop</green>|<green>status</green>|<green>hold</green> [server] [duration]</white>"
  helpHeader:     "<gray>ServerManager commands:</gray>"
  holdUsage:      "<gray>Usage:</gray> <white>/sm hold <green><server></green> <green><duration|forever|clear></green></white>"
  holdSet:        "<green><white><server></white> will stay online for the next <duration>.</green>"
  holdStatus:     "<gray><white><server></white> hold remaining: <duration>.</gray>"
  holdCleared:    "<yellow>Hold cleared for <white><server></white>.</yellow>"
  holdNotActive:  "<gray><white><server></white> is not currently held.</gray>"
  holdInvalidDuration: "<red>Unknown duration '<duration>'.</red>"
  holdStatusSuffix: "<gray>(hold: <duration>)</gray>"
  startingQueued: "<yellow>Starting <white><server></white>… You'll be sent automatically.</yellow>"
  startFailed:    "<red>Failed to start <white><server></white>. Try again.</red>"
  readySending:   "<green><white><server></white> is ready. Sending you now…</green>"
  timeout:        "<red><white><server></white> didn't come up in time.</red>"
  unknownServer:  "<red>Unknown server <white><server></white>.</red>"
  started:        "<green>Started <white><server></white>.</green>"
  alreadyRunning: "<yellow><white><server></white> is already running.</yellow>"
  stopped:        "<yellow>Stopped <white><server></white>.</yellow>"
  stopKick:       "<red><white><server></white> is stopping. Please rejoin later.</red>"
  alreadyStopped: "<gray><white><server></white> is not running.</gray>"
  statusHeader:   "<gray>Managed servers:</gray>"
  statusLine:     "<white><server></white>: <state>"
  stateOnline:    "<green>online</green>"
  stateOffline:   "<red>offline</red>"
  bannedMessage:  "<red>You are banned.</red><newline><gray>Reason: <reason></gray><newline><gray>Expires: <expiry></gray>"
  mutedMessage:   "<red>You are muted.</red><newline><gray>Reason: <reason></gray><newline><gray>Expires: <expiry></gray>"
  warnMessage:    "<yellow>You have been warned: <reason></yellow>"

servers:
  lobby:
    startOnJoin: true
    workingDir: "../lobby"
    startCommand: "./start.sh"
    stopCommand: "stop"
    logToFile: true
    logFile: "logs/proxy-managed-lobby.log"
    vanillaWhitelistBypassesNetwork: true
    mirrorNetworkWhitelist: true
    autoRestartHoldTime: "05:00"

  survival:
    startOnJoin: false
    workingDir: "../survival"
    startCommand: "./start.sh"
    stopCommand: "stop"
    logToFile: true
    logFile: "logs/proxy-managed-survival.log"
    vanillaWhitelistBypassesNetwork: false
    mirrorNetworkWhitelist: false
    autoRestartHoldTime: ""

whitelist:
  enabled: true
  bind: "0.0.0.0"
  port: 8081
  baseUrl: "http://127.0.0.1:8081"
  kickMessage: "You are not whitelisted. Visit <url> and enter your username and code <code>."
  codeLength: 6
  codeTtlSeconds: 900
  dataFile: "network-whitelist.yml"
  allowVanillaBypass: true
  pageTitle: "Network Access"
  pageSubtitle: "Enter the code shown in-game to whitelist your account."
  successMessage: "Success! You are now whitelisted. You may rejoin the server."
  failureMessage: "Invalid or expired code. Please try again from in-game."
  buttonText: "Verify & Whitelist"

moderation:
  enabled: true
  dataFile: "moderation.yml"

forcedHosts: {}
```

Key points:
- Exactly one server must have `startOnJoin: true`. That backend becomes the "primary" server shown in the MOTD.
- `workingDir` is where the start command runs. Relative paths resolve from the Velocity root directory.
- `startCommand` executes inside `workingDir`. Include `nogui` if you do not want the vanilla GUI.
- `stopCommand` is written to the server stdin during graceful shutdown (`stop` for Paper).
- If `logToFile` is true, stdout/stderr is redirected to `logFile`. Paths are resolved relative to `workingDir` when not absolute.
- Each server entry may set `vanillaWhitelistBypassesNetwork` to grant players on that backend's `whitelist.json` access to the network whitelist automatically, and `mirrorNetworkWhitelist` to push every accepted player back into that backend's vanilla whitelist (including via `/whitelist add` while it is online). When omitted, the primary server keeps the legacy behavior (bypass + mirror) and other servers remain opt-in.
- `startupGraceSeconds` is added once to the proxy-empty stop timer if a server just started to avoid killing a fresh boot.
- Network whitelist (`whitelist:` block) is optional. When enabled, joining players are checked against `network-whitelist.yml`. Non-whitelisted players are kicked with a short URL and one-time code and can redeem it through the built-in HTTP form.
- `allowVanillaBypass: true` remains the default for the primary backend when the per-server flag is not set. Set it to `false` if you want even the primary server to ignore its vanilla whitelist entirely.
- `moderation:` controls the proxy-level moderation registry used by the standalone `/ban`, `/ipban`, `/mute`, `/warn`, and list commands. When enabled (default) bans are enforced at login, before any backend starts.

## Network Whitelist Flow
1. Player joins Velocity.
2. If their UUID or username exists in `network-whitelist.yml`, or (optionally) in the primary backend's `whitelist.json`, they proceed normally.
3. Otherwise the plugin:
   - Issues a short numeric code and stores it in-memory for that UUID.
   - Disconnects the player with the configured `kickMessage`, replacing `<url>` and `<code>`.
   - Keeps backend servers off until the player is verified (avoiding accidental auto-starts).
4. The player visits the web form served by the embedded HTTP server (reachable at `baseUrl`).
5. After entering their in-game name and code, the plugin validates the submission, writes the entry to `network-whitelist.yml`, and shows a success message instructing them to reconnect.

Notes:
- `bind` / `port` define the listen address. Use a reverse proxy (or change `baseUrl`) to expose the form over HTTPS.
- Codes expire after `codeTtlSeconds` and are one-time. Requesting a new code replaces the old one.
- `network-whitelist.yml` is written atomically and can be edited manually while Velocity is offline if needed.

## Forced-Host Overrides
- Each hostname under `forcedHosts` maps to a managed server name. When a player joins through that host (including their very first proxy join), the plugin starts that backend instead of the primary start-on-join server.
- Optional `motd` blocks mirror the top-level MOTD structure; any missing field falls back to the global text.
- `kickMessage` lets you display host-specific messaging when a backend must boot before the player can connect.
- Hostnames are matched case-insensitively and may include ports (which are stripped). Leave the section empty to rely on the global defaults.
- MOTD overrides (and the `{server}` placeholder) require the status ping to reach Velocity with the same hostname. If you front Velocity with a proxy/DDoS shield such as TCPShield or Cloudflare Spectrum, register each forced-host subdomain so the ping is not rewritten to the root domain; otherwise the MOTD falls back to the primary server.

Example:

```yaml
forcedHosts:
  creative.example.com:
    server: creative
    motd:
      online: "<gray>Creative Realm</gray> <green>[Online]</green>"
    kickMessage: "Creative is waking up—please reconnect in a moment."
```

## Proxy MOTD States
The Velocity ping uses the primary server status by default, or the forced-host override target when a hostname is configured. All MOTD strings may reference `<server>` (or `{server}`) to display the managed server name currently being tracked.
- **Offline**: primary backend process is not running.
- **Starting**: process has been launched but has not yet responded to a ping.
- **Online**: ping succeeded recently (player connections will succeed immediately).

You can style each pair of lines independently. If `starting` or `starting2` is blank, the plugin falls back to the online/offline text in that slot.

## Lifecycle and Auto-Start Flow
1. Player joins an empty proxy.
   - Plugin starts the primary server and sends the player a kick containing `kickMessage`.
   - When the proxy becomes empty again, a stop-all timer is scheduled with `stopGraceSeconds + startupGraceSeconds`.
2. Player uses `/server <target>` (or a GUI menu) where `<target>` is offline but listed in `servers`.
   - Plugin launches the backend, denies the immediate connect, and sends `messages.startingQueued`.
   - A scheduler polls the backend with pings; on success it sends `messages.readySending` and auto-connects the player.
   - If the backend fails to come up within 90 seconds, the player sees `messages.timeout` and the backend is stopped if nobody reached it.
3. When a backend becomes empty, the plugin schedules a stop after `stopGraceSeconds`. Any player activity on that server cancels the timer.
4. When the proxy has zero players, a stop-all is scheduled (with a one-off grace bump if a server just started). Any new player cancels it.

## Commands
Root command aliases: `/servermanager`, `/sm`

| Subcommand | Permission checks*                         | Description |
|------------|--------------------------------------------|-------------|
| `status`   | `servermanager.command.status` (wildcards supported) | Lists each managed server with an online/offline tag and marks the primary. |
| `start`    | `servermanager.command.start`               | Boots the named managed server if it is offline. |
| `stop`     | `servermanager.command.stop`                | Sends the graceful stop command to the backend. |
| `help`     | _None_                                      | Prints the command overview along with `hold` syntax. |
| `hold`     | `servermanager.command.hold`                | Keeps the backend running for the requested duration (accepts `30m`, `2h`, `1h30m`, or `forever`). Starts the server automatically if it is offline. |
| `reload`   | `servermanager.command.reload`              | Reloads configuration, restarts the whitelist web server, syncs whitelist data, and keeps running managed servers online (stopping only those removed from config). |
| `whitelist`| `servermanager.command.whitelist`           | Views/adds/removes entries from the network whitelist and any managed vanilla `whitelist.json`, and toggles vanilla whitelist enforcement per server. |

Durations default to minutes when no unit is supplied. Use `forever` for an indefinite hold. Run `/sm hold <server>` to check the remaining time or `/sm hold <server> clear` to release it early. When a server is held forever and `autoRestartHoldTime` is set (HH:mm), it will automatically restart at that daily time with in-game warnings at 1 minute and 5 seconds.

\* Any of `servermanager.command.*`, `servermanager.*`, or legacy `startonjoin.*` nodes also satisfy the checks. Console sources bypass permission checks automatically.

Legacy single-action commands (`/svstart`, `/svstop`, `/svstatus`) are kept for compatibility but delegate to the same logic.

`/sm reload` refreshes the plugin runtime and whitelist services without interrupting running managed servers. Only servers removed from the configuration are stopped during the reload.

### Whitelist management (`/sm whitelist`)
- `network list` shows the first 20 entries in `network-whitelist.yml` (with a count of any remaining). Use `network add <player|uuid> [name]` to add an online player or explicit UUID, and `network remove <player|uuid>` to revoke access. Adding a player mirrors them into every server that sets `mirrorNetworkWhitelist: true`.
- `vanilla <server> list` dumps the current contents of that backend's `whitelist.json`. `vanilla <server> add <player|uuid> [name]` accepts either a UUID/online player or just a username (UUID preferred). Removals accept UUIDs or names as well, and active backends also receive a live `/whitelist add|remove ...` command for immediate effect. Use `vanilla <server> on|off|status` to enable/disable or query the backend's vanilla whitelist (writes `server.properties` and sends `/whitelist on|off` when online).
- UUID arguments may omit dashes. When only a UUID is supplied the plugin tries to reuse the last known username from the network whitelist, ban list, or live player list.

### Moderation commands (standalone)
- `/ban <player> [duration] [reason]` — bans by UUID; duration accepts `1d`, `2h30m`, or `forever` (default permanent). Kicks immediately.
- `/ipban <player|ip> [duration] [reason]` — bans by IP; if a player is online their current IP is used. Kicks immediately when online.
- `/unban <player|ip>` — removes UUID/IP bans.
- `/mute <player> [duration] [reason]` and `/unmute <player>` — mutes block chat and surface a clear muted message with expiry.
- `/warn <player> [reason]` — notifies the player (not persisted).
- `/banlist`, `/mutelist` — show the most recent 20 entries with expiry info.
Permissions: `servermanager.moderation.ban`, `.ipban`, `.unban`, `.mute`, `.unmute`, `.warn`, `.view` (lists). Console bypasses checks. Bans are enforced at login, before any backend starts.

## Logging
- Velocity logs key lifecycle actions: process starts, stop scheduling, cancellations, timeouts, and migration messages.
- When `logToFile` is enabled, backend stdout/stderr is piped to the configured file and not echoed to the proxy console.

## Troubleshooting
- **Config refuses to load**: Ensure `servers:` contains at least one entry and exactly one has `startOnJoin: true`.
- **MOTD always offline**: Confirm the primary backend is registered with Velocity and reachable; successful pings are required before the MOTD flips to "online".
- **Player stuck waiting**: Check backend logs for boot errors. If the process starts but never replies to pings, the auto-connect poller will eventually time out.
- **Backends never stop**: Confirm `stopGraceSeconds` is set, that players actually disconnect, no admin hold is active, and that the stop command is correct. A background sweep now re-schedules stops for empty-but-running backends, so persistent stragglers will log a warning in the Velocity console (especially when `logToFile` is disabled).

## Development Notes
- Package namespace defaults to `com.example.soj`. Rename the package and update build files if you fork.
- `ServerManagerPlugin` handles lifecycle wiring, configuration migration, and command registration.
- `ServerProcessManager` and `ManagedServer` encapsulate per-server process control.
- `PlayerEvents` orchestrates player flow, MOTD updates, start queues, and shutdown scheduling.
- The project targets Java 17 and shades its dependencies; keep that baseline when contributing.
