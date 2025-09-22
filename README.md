# ServerManager

ServerManager (Velocity plugin)
===============================

Start/stop backend Paper/Spigot servers on demand from a Velocity proxy.

Highlights

*   Start on first join (designated “primary” server).
    
*   Start on “/server ” for any managed server and auto-connect the player when it’s actually ready.
    
*   MiniMessage MOTD (two lines; offline/online variants).
    
*   Graceful auto-stop when a backend (or the whole proxy) becomes empty.
    
*   Optional per-server log files to keep Velocity console clean.
    
*   All user-facing messages configurable.
    

GoalKeep no backend running unless a player is online.

Requirements
------------

*   Java 17 or newer.
    
*   Velocity 3.4.0-SNAPSHOT-534 (or the exact API version you target).
    
*   Maven (to build).
    
*   Paper/Spigot backends that can be run via a command in their working directory.
    

Build
-----

1.  mvn install:install-file -Dfile=/path/to/velocity-3.4.0-SNAPSHOT-534.jar -DgroupId=com.velocitypowered -DartifactId=velocity-api -Dversion=3.4.0-SNAPSHOT-534 -Dpackaging=jar
    
2.  mvn clean package
    

The shaded artifact will be created at:target/servermanager-\-shaded.jar

Install
-------

1.  Copy the shaded JAR to your Velocity plugins/ folder.
    
2.  Start Velocity once to generate the default configuration at:plugins/servermanager/config.yml
    
3.  Edit the configuration (see below), then restart Velocity.
    

Configuration
-------------

File:plugins/servermanager/config.yml

Full example (two servers):

kickMessage: "Server Starting"startupGraceSeconds: 15stopGraceSeconds: 60

motd:offline: "gradient:#ff5555:#ff2222My Network"offline2: "Server Offline - Join to Start"online: "gradient:#22dd55:#11aa44My Network"online2: "Server Online"

messages:startingQueued: "Starting {server}… you’ll be sent when it’s ready."readySending: "{server} is ready — sending you now!"startFailed: "Couldn’t start {server}. Please try again later."timeout: "{server} took too long to start. Try again in a moment."unknownServer: "Unknown server: {server}"

servers:testing:startOnJoin: trueworkingDir: "../servers/testing"startCommand: "java -Xms4096M -Xmx4096M -jar paper-1.21.8-49.jar nogui"stopCommand: "stop"logToFile: truelogFile: "logs/proxy-managed-testing.log"

smp:startOnJoin: falseworkingDir: "../servers/smp"startCommand: "java -Xms2048M -Xmx4096M -jar paper-1.21.8-49.jar nogui"stopCommand: "stop"logToFile: truelogFile: "logs/proxy-managed-smp.log"

Notes

*   workingDir is where the backend process is launched (relative to the Velocity root or absolute).
    
*   startCommand runs inside workingDir (include “nogui” if you do not want the GUI).
    
*   stopCommand is written to the backend’s stdin (e.g., “stop” for Paper).
    
*   logToFile true routes backend stdout/stderr to logFile, keeping Velocity console quieter.
    
*   Exactly one server should have startOnJoin: true (this is considered the primary).
    

Commands and Permissions
------------------------

Velocity-side commands (aliases):

*   /servermanager
    
*   /sm
    

Subcommands:

*   /sm start — start a managed server if it is offline.
    
*   /sm stop — stop a managed server gracefully.
    
*   /sm status — list all managed servers and their state.
    

Suggested permission nodes (assign with LuckPerms or similar):

*   servermanager.command.start
    
*   servermanager.command.stop
    
*   servermanager.command.status
    
*   servermanager.command.\* (umbrella)
    

Using Velocity’s built-in /server:

*   /server will attempt to connect. If the server is offline but managed by ServerManager:
    
    *   The plugin starts it.
        
    *   The player sees an in-game “starting …” message.
        
    *   The player is automatically connected when a ping confirms the backend is ready.
        
    *   If already online, the player connects immediately.
        

Behavior Details
----------------

Primary start on first join

*   When the proxy has zero players and the primary backend is offline, the first player to join triggers a start.
    
*   The player is immediately kicked with the configurable kickMessage (“Server Starting”) so they can retry from their server list while it boots.
    
*   The plugin also arms a proxy-empty stop-all safety timer in case nobody returns.
    

Accurate MOTD

*   The MOTD (two lines) is MiniMessage-formatted.
    
*   It shows the “offline” variant until a successful ping confirms the primary backend is actually ready.
    
*   Then it switches to the “online” variant.
    
*   Newlines should be expressed in MOTD via two separate lines in config (offline/offline2 or online/online2). You may also use inside the MiniMessage string if desired.
    

Start on /server with auto-connect

*   Works for any managed server (primary or non-primary).
    
*   If the target is offline:
    
    *   The plugin starts it and denies the immediate connect.
        
    *   The player remains on the proxy, sees a “starting … you’ll be sent when it’s ready” message, and is auto-sent once the server pings successfully.
        
    *   Auto-send attempts time out after a configurable period in code (e.g., 90 seconds). On timeout, a configurable message is shown and the backend is stopped if nobody joined.
        
*   If the target is online:
    
    *   The player connects normally.
        

Per-server graceful stop

*   When a backend becomes empty, the plugin schedules a stop after stopGraceSeconds.
    
*   If someone joins that server again before the timer fires, the pending stop is canceled.
    
*   This ensures no backend remains running without players.
    

Proxy-empty stop-all safety

*   When the entire proxy has zero players, the plugin schedules a stop-all after stopGraceSeconds.
    
*   If a server was just started recently, startupGraceSeconds is added once to the delay to avoid an immediate shutdown of a freshly started server.
    
*   If someone joins the proxy before the timer fires, the pending stop-all is canceled.
    

No duplicate “ready” messages

*   The plugin de-duplicates concurrent ping completions so players only see one “server is ready — sending you now” message per request.
    

MiniMessage Quick Reference
---------------------------

Examples of common tags:

*   Colors: text, text, text, etc.
    
*   Styles: , , , , .
    
*   Gradient: gradient:#ff5555:#ff2222Your Text.
    
*   Newline in MOTD: either use two lines in config (offline/offline2 or online/online2), or include inside the string.
    

Message placeholders

*   {server}, (server), or are treated the same. They resolve to the server name.
    
*   {player}, (player), or resolve to the player’s username.
    
*   {state} or is available if you wish to include it in your own custom messages (not required by defaults).
    

Troubleshooting
---------------

Velocity console shows both proxy and backend logs

*   Enable logToFile: true for each server so their output goes to their own logFile.
    

MOTD says online too early

*   The plugin marks MOTD “online” only after a successful ping to the primary backend. If you see early flips, ensure the primary server is reachable by Velocity (IP/port, firewall).
    

“Unknown server” when running /server

*   Ensure the name exists under servers: in config.yml and that Velocity also has a registered server with that exact name.
    

YAML parsing errors

*   Do not include any “!!class” tags in your YAML. Use only plain keys and values.
    

Backends do not stop

*   Check stopGraceSeconds, startupGraceSeconds, and whether players remain online on that backend or elsewhere on the proxy.
    
*   Verify that stopCommand is correct (“stop” for Paper).
    
*   Confirm workingDir and startCommand are correct and that processes are tracked correctly by the OS.
    

Ports and addresses

*   Ensure each backend’s server.properties uses a port that matches what Velocity expects and that Velocity can reach it (often 127.0.0.1: for local).
    

Development Notes
-----------------

*   Java 17 target; shaded JAR build via Maven.
    
*   Dependencies:
    
    *   Velocity API (scope provided).
        
    *   SnakeYAML (relocated/shaded to avoid proxy conflicts).
        
    *   Kyori Adventure MiniMessage.
        
*   Main components:
    
    *   ServerProcessManager — starts/stops processes, tracks PIDs and recent starts.
        
    *   listeners/PlayerEvents — MOTD, start/stop logic, auto-send, queueing and guards.
        
    *   Commands — /servermanager and /sm aliases; includes start/stop/status subcommands.
        
*   Package name in sources defaults to com.example.soj or your chosen namespace; if you rename, keep package declarations and file paths consistent.

![alt text](https://github.com/e1ixyz/ServerManager/blob/main/img/join.png)

![alt text](https://github.com/e1ixyz/ServerManager/blob/main/img/offline.png)

![alt text](https://github.com/e1ixyz/ServerManager/blob/main/img/online.png)