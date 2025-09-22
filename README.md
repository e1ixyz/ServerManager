# ServerManager

ServerManager (Velocity plugin)

Start/stop backend Paper/Spigot servers on demand from a Velocity proxy, with MiniMessage MOTD, graceful stop timers, and /server auto-start + auto-connect for non-primary servers.

✅ Goal: keep no backend running unless a player is online.
🧭 Primary server can be configured to start when the first player joins from the Minecraft menu (player is kicked with a friendly “starting” message, then rejoins).
🛰 Non-primary targets can be started by /server <name>; players stay on the proxy, see “starting…” in chat, and are auto-sent once the server is ready.

Features

Start on join (primary only): first player joining an empty proxy triggers the primary backend to start; player is kicked with a configurable message (so they can retry).

Start on /server <name> (any managed backend):

If target is offline: start it, show in-game “starting…” message, auto-send when ready.

If already online: just connect.

Accurate MOTD (MiniMessage):

Shows offline text until a successful ping confirms the primary backend is truly up (not just the process started).

Two configurable lines for both offline and online states.

Graceful stop:

Per-server: when a backend becomes empty, schedule a stop after stopGraceSeconds.

Proxy-empty safety: when the entire proxy is empty, stop all managed backends after stopGraceSeconds (optionally bumped by startupGraceSeconds if a server just started).

Logs: Optionally pipe each backend’s stdout/stderr to a file (separate from Velocity console).

Fully configurable messages (kick, starting, ready, failure, timeout, etc.).

Multiple servers supported; one primary server can be designated to start on join.

Requirements

Java 17+

Velocity 3.4.0-SNAPSHOT-534 (or matching your local Velocity jar)

Maven (for building)

Backends (e.g., Paper) runnable via a shell command in their working directory.

Build & Install

Build

mvn clean package


The shaded jar will be at:

target/servermanager-<version>-shaded.jar


Install

Copy the shaded jar into your Velocity plugins/ folder.

Start Velocity once to generate the default config:

plugins/servermanager/config.yml


Configure (see below), then restart Velocity.

Configuration

plugins/servermanager/config.yml

Minimal example (two servers)
kickMessage: "Server Starting"
startupGraceSeconds: 15   # extra time added to the stop-all timer right after a start
stopGraceSeconds: 60      # how long we wait before stopping an empty backend

# MiniMessage MOTD (two lines each). Colors/tags allowed.
motd:
  offline:  "<bold><gradient:#ff5555:#ff2222>My Network</gradient></bold>"
  offline2: "<red>Server Offline - Join to Start</red>"
  online:   "<bold><gradient:#22dd55:#11aa44>My Network</gradient></bold>"
  online2:  "<green>Server Online</green>"

# All user-facing messages (MiniMessage). {server} and {player} placeholders supported.
messages:
  startingQueued: "<yellow>Starting <white>{server}</white>… you’ll be sent when it’s ready.</yellow>"
  readySending: "<green>{server}</green> is ready — sending you now!"
  startFailed: "<red>Couldn’t start {server}. Please try again later.</red>"
  timeout: "<red>{server}</red> took too long to start. Try again in a moment."
  unknownServer: "<red>Unknown server</red>: <white>{server}</white>"

# Managed servers. Exactly one should have startOnJoin: true to be the "primary".
servers:
  testing:
    startOnJoin: true
    workingDir: "../servers/testing" # path to the backend working directory
    startCommand: "java -Xms4096M -Xmx4096M -jar paper-1.21.8-49.jar nogui"
    stopCommand: "stop"
    logToFile: true
    logFile: "logs/proxy-managed-testing.log"

  smp:
    startOnJoin: false
    workingDir: "../servers/smp"
    startCommand: "java -Xms2048M -Xmx4096M -jar paper-1.21.8-49.jar nogui"
    stopCommand: "stop"
    logToFile: true
    logFile: "logs/proxy-managed-smp.log"


Notes

workingDir is where the backend is launched (relative to Velocity root or absolute).

startCommand runs inside workingDir. Include nogui if you don’t need the GUI.

stopCommand is written to the backend’s stdin (e.g., stop for Paper).

logToFile writes backend output to logFile (created relative to Velocity root unless absolute).

Only one server should have startOnJoin: true (the primary).

Commands & Permissions
Commands (Velocity side)

/servermanager, /sm

/sm start <server> – start a managed server (if offline)

/sm stop <server> – stop a managed server (graceful)

/sm status – show status of all managed servers

These command names/aliases come from the plugin; permissions below are suggested nodes you can assign with LuckPerms (or your permissions plugin).

Suggested permissions

servermanager.command.start

servermanager.command.stop

servermanager.command.status

(Optional umbrella) servermanager.command.*

Using Velocity’s /server

Players (no special permission required) can use:

/server <name>


If <name> is offline and managed, the plugin starts it, informs the player, and auto-sends them when it’s ready.

If it’s online, they connect immediately.

How it works (behavior)

Primary join from main menu (proxy empty)

Start primary backend → kick with kickMessage.

Player retries; once backend is up, they can join normally.

Non-primary /server <name>

If offline → start it, message “starting…”, auto-connect on ping success.

If online → connect immediately.

Stop rules

If a backend becomes empty, schedule a stop in stopGraceSeconds.

If the whole proxy becomes empty, schedule stop-all in stopGraceSeconds
(and if a backend just started, the timer is bumped by startupGraceSeconds once).

MOTD logic

Shows offline until a periodic ping succeeds for the primary backend, then shows online.

Uses your MiniMessage strings (motd.offline/online and offline2/online2).

No duplicates

Auto-send “ready” message is single-fire per player per request; concurrent pings are de-duplicated.

MiniMessage quick tips

Colors: <red>…</red>, <green>…</green>, <yellow>…</yellow>, etc.

Styles: <bold>, <italic>, <underlined>, <strikethrough>, <obfuscated>.

Gradients: <gradient:#ff5555:#ff2222>Text</gradient>.

Newlines: use <newline> in MOTD (Velocity supports a two-line description).

Placeholders in messages:

{server} or <server> → replaced with the server name

{player} or <player> → replaced with the player name

Troubleshooting

Double “ready” messages: fixed—messages are gated to fire once per request.

MOTD shows online too early: fixed—MOTD flips only after a successful ping.

Backend logs in the Velocity console: enable logToFile: true to reduce console noise; each backend logs separately.

YAML errors: ensure you do not include a !!class tag in the config. Stick to plain YAML keys/values.

Ports & addresses: make sure each backend’s server.properties uses a port that matches Velocity’s backend config and is reachable from the proxy (often 127.0.0.1:<port> for local testing).

Development

Java 17 target; built with Maven (shaded jar).

Dependencies:

Velocity API (scope provided)

SnakeYAML (relocated/shaded)

Kyori Adventure MiniMessage

Main classes:

ServerProcessManager – starts/stops processes, tracks PIDs & recent starts.

listeners/PlayerEvents – core behavior (MOTD, start/stop logic, auto-send).

Commands:

ServerManagerCmd (root), StartCmd, StopCmd, StatusCmd.

![alt text](https://github.com/e1ixyz/ServerManager/blob/main/img/join.png)

![alt text](https://github.com/e1ixyz/ServerManager/blob/main/img/offline.png)

![alt text](https://github.com/e1ixyz/ServerManager/blob/main/img/online.png)