package dev.e1ixyz.servermanager;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Config {
  public String kickMessage = "Server Starting";
  public int startupGraceSeconds = 15;
  public int stopGraceSeconds = 60;
  public int reconnectWindowSeconds = 60;

  public Motd motd = new Motd();
  public ServerMenu serverMenu = new ServerMenu();
  public Map<String, ServerConfig> servers = new LinkedHashMap<>();
  /** Optional forced-host overrides keyed by hostname (case-insensitive). */
  public Map<String, ForcedHost> forcedHosts = new LinkedHashMap<>();
  public Messages messages = new Messages();
  public Whitelist whitelist = new Whitelist();
  public Maps maps = new Maps();
  public Moderation moderation = new Moderation();
  public AutoIpBan autoIpBan = new AutoIpBan();
  public Compatibility compatibility = new Compatibility();
  public Discord discord = new Discord();

  public static final class Motd {
    public String offline  = "<gray>Your Network</gray> <gray>- <white><server></white></gray>";
    public String offline2 = "<red><bold>Server Offline - Join to Start</bold></red>";
    public String starting = "<yellow><bold>Server Starting</bold></yellow> <white><server></white>";
    public String starting2 = "<gray>Please wait...</gray>";
    public String online   = "<gray>Your Network</gray> <gray>- <white><server></white></gray>";
    public String online2  = "<green><bold>Server Online</bold></green>";
  }

  /**
   * Reskin for the {@code /server} chat menu (replaces Velocity's built-in). All strings are
   * MiniMessage. Placeholders: entry -&gt; &lt;server&gt; &lt;count&gt; &lt;state&gt;;
   * tooltipHeader -&gt; &lt;server&gt; &lt;count&gt;; tooltipPlayer -&gt; &lt;player&gt;; tooltipMore -&gt; &lt;count&gt;.
   * Toggling {@code enabled} takes effect on proxy restart; every other field is live on /sm reload.
   */
  public static final class ServerMenu {
    public boolean enabled = true;
    public String header  = "<gradient:#00d2ff:#3a7bd5><bold>Network Servers</bold></gradient>";
    public String footer  = "<gray><italic>Hover a server to see who's on. Click to join.</italic></gray>";
    public String entry   = "  <gray>▸</gray> <aqua><server></aqua> <dark_gray>(<count> online)</dark_gray> <version> <state>";
    /** Rendered in the entry's <version> slot from the backend's last ping; blank when offline/unknown. */
    public String version = "<dark_gray>[<version>]</dark_gray>";
    public String stateOnline  = "<green>●</green>";
    public String stateOffline = "<red>●</red>";
    public String tooltipHeader = "<aqua><bold><server></bold></aqua> <gray>— <count> online</gray>";
    public String tooltipPlayer = "<white>• <player></white>";
    public String tooltipEmpty  = "<dark_gray><italic>(empty)</italic></dark_gray>";
    public String tooltipMore   = "<dark_gray><italic>…and <count> more</italic></dark_gray>";
    public int tooltipMaxPlayers = 15;
    public boolean showOffline = true;
  }

  public static final class Messages {
    public String startingQueued = "<yellow>Starting <white><server></white>… You’ll be sent automatically.</yellow>";
    public String startFailed    = "<red>Failed to start <white><server></white>. Try again.</red>";
    public String readySending   = "<green><white><server></white> is ready. Sending you now…</green>";
    public String timeout        = "<red><white><server></white> didn’t come up in time.</red>";
    public String unknownServer  = "<red>Unknown server <white><server></white>.</red>";
    public String noPermission   = "<red>You don’t have permission.</red>";
    public String usage          = "<gray>Usage:</gray> <white>/sm <green>start</green>|<green>stop</green>|<green>status</green>|<green>hold</green>|<green>updateplugins</green>|<green>preference</green> ...</white>";
    public String helpHeader     = "<gray>ServerManager commands:</gray>";
    public String holdUsage      = "<gray>Usage:</gray> <white>/sm hold <green><server></green> <green><duration|forever|clear></green></white>";
    public String updatePluginsUsage = "<gray>Usage:</gray> <white>/sm updateplugins <green><server></green> <green><waiting></green></white>";
    public String updatePluginsBusy = "<yellow>Another plugin update workflow is already running.</yellow>";
    public String updatePluginsPreparing = "<yellow>Starting <white><waiting></white> before restarting <white><server></white>...</yellow>";
    public String updatePluginsMoving = "<yellow><white><waiting></white> is ready. Moving all players before restarting <white><server></white>...</yellow>";
    public String updatePluginsRestarting = "<yellow>Restarting <white><server></white> now...</yellow>";
    public String updatePluginsReturning = "<green><white><server></white> is back online. Sending players there now...</green>";
    public String updatePluginsComplete = "<green>Plugin update flow complete. Players were returned to <white><server></white>.</green>";
    public String updatePluginsFailed = "<red>Plugin update flow failed: <reason></red>";
    public String updatePluginsSameServer = "<red><white><server></white> and <white><waiting></white> must be different servers.</red>";
    public String updatePluginsPlayerWaiting = "<yellow>Plugin updates are in progress. Sending you to <white><waiting></white>...</yellow>";
    public String updatePluginsPlayerReturning = "<green>Plugin updates finished. Sending you to <white><server></white>...</green>";
    public String holdSet        = "<green><white><server></white> will stay online for the next <duration>.</green>";
    public String holdStatus     = "<gray><white><server></white> hold remaining: <duration>.</gray>";
    public String holdCleared    = "<yellow>Hold cleared for <white><server></white>.</yellow>";
    public String holdNotActive  = "<gray><white><server></white> is not currently held.</gray>";
    public String holdInvalidDuration = "<red>Unknown duration '<duration>'.</red>";
    public String holdStatusSuffix = "<gray>(hold: <duration>)</gray>";
    public String holdRestartWarning1m = "<yellow><white><server></white> will restart in 1 minute.</yellow>";
    public String holdRestartWarning5s = "<red><white><server></white> will restart in 5 seconds.</red>";
    public String holdRestartNow = "<red><white><server></white> is restarting now...</red>";
    public String reloadSuccess  = "<green>ServerManager reloaded successfully.</green>";
    public String reloadFailed   = "<red>Reload failed. Check console for details.</red>";
    public String notWhitelistedBackend = "<red>You are not whitelisted on <white><server></white>.</red>";
    public String started        = "<green>Started <white><server></white>.</green>";
    public String alreadyRunning = "<yellow><white><server></white> is already running.</yellow>";
    public String stopped        = "<yellow>Stopped <white><server></white>.</yellow>";
    public String stopKick       = "<red><white><server></white> is stopping. Please rejoin later.</red>";
    public String alreadyStopped = "<gray><white><server></white> is not running.</gray>";
    public String craftyManaged  = "<red>Lifecycle is managed by Crafty Controller — use the Crafty panel to start/stop/restart <white><server></white>.</red>";
    public String craftyOfflineKick   = "<red><white><server></white> is offline right now.</red><newline><gray>Ask an admin to bring it online, then rejoin.</gray>";
    public String craftyOfflineQueued = "<yellow><white><server></white> is offline.</yellow><gray> You'll be sent automatically if it comes online.</gray>";
    public String statusHeader   = "<gray>Managed servers:</gray>";
    public String statusLine     = "<white><server></white>: <state>";
    public String stateOnline    = "<green>online</green>";
    public String stateOffline   = "<red>offline</red>";
    public String bannedMessage  = "<red>You are banned.</red><newline><gray>Reason: <reason></gray><newline><gray>Expires: <expiry></gray>";
    public String mutedMessage   = "<red>You are muted.</red><newline><gray>Reason: <reason></gray><newline><gray>Expires: <expiry></gray>";
    public String warnMessage    = "<yellow>You have been warned: <reason></yellow>";
    public List<String> stealthBanKickMessages = new ArrayList<>(defaultStealthBanKickMessages());

    public static List<String> defaultStealthBanKickMessages() {
      return List.of(
          "Internal Exception: java.net.SocketException: Connection reset",
          "Disconnected",
          "Failed to connect to server",
          "Invalid Session (Try restarting your game)",
          "Failed to verify username!",
          "Connection lost",
          "Timed out"
      );
    }
  }

  public static final class Whitelist {
    public boolean enabled = false;
    public String bind = "127.0.0.1"; // localhost-only; cloudflared reaches it locally
    public int port = 8081;
    public String baseUrl = "http://127.0.0.1:8081";
    public String kickMessage = "You are not whitelisted. Join our Discord ( <url> ) and run /link <code>";
    public int codeLength = 6;
    public int codeTtlSeconds = 900;
    public String dataFile = "network-whitelist.yml";
    public boolean allowVanillaBypass = true;
    public String pageTitle = "Network Access";
    public String pageSubtitle = "Enter the code shown in-game to whitelist your account.";
    public String successMessage = "Success! You are now whitelisted. You may rejoin the server.";
    public String failureMessage = "Invalid or expired code. Rejoin in-game to get a new one.";
    public String buttonText = "Verify & Whitelist";
    // Redemption brute-force throttle (per client IP, sliding window).
    public int maxAttempts = 10;
    public int windowSeconds = 300;
    public String rateLimitedMessage = "Too many attempts. Please wait a few minutes and try again.";
  }

  /** Discord bot that replaces the whitelist website: members run /link|/whitelist &lt;code&gt;. */
  public static final class Discord {
    public boolean enabled = false;
    public String guildId = "";   // Discord server (guild) id; slash commands register here for instant visibility
    public String invite = "";    // invite URL shown in the in-game kick message (<url> placeholder)
    public String linkSuccess = "Linked! You are now whitelisted — rejoin the Minecraft server.";
    public String linkFailure = "That code is invalid or expired. Rejoin Minecraft to get a fresh code.";
    // The bot token is read from the SM_DISCORD_TOKEN environment variable, never from this file.
  }

  public static final class Maps {
    public boolean enabled = false;
    public String bind = "127.0.0.1"; // localhost-only; cloudflared reaches it locally
    public int port = 8090;
    public String pageTitle = "Merp Network Maps";
    public List<MapTab> tabs = new ArrayList<>();
  }

  public static final class MapTab {
    public String label;
    public String path;    // same-origin path segment, e.g. "smp" -> served at /smp/
    public String backend; // local Dynmap base to proxy to, e.g. "http://127.0.0.1:8124"
  }

  public static final class ForcedHost {
    /** Managed server name Velocity routes this host to. */
    public String server;
    /** Optional MOTD overrides for this host; falls back to global MOTD when null. */
    public Motd motd;
    /** Optional kick message when start-on-join kicks the player. */
    public String kickMessage;
  }

  public static final class Moderation {
    public boolean enabled = true;
    public String dataFile = "moderation.yml";
  }

  public static final class AutoIpBan {
    public boolean enabled = false;
    public boolean dryRun = false;
    public int banMinutes = 60;
    public String reasonPrefix = "Auto-ban";
    public java.util.List<String> trustedIps = new java.util.ArrayList<>();
    public java.util.List<String> trustedCidrs = new java.util.ArrayList<>();
    public AutoThresholds thresholds = new AutoThresholds();
  }

  public static final class AutoThresholds {
    public Threshold connections = new Threshold();
    public Threshold pings = new Threshold();
    public Threshold badUsernames = new Threshold();
  }

  public static final class Threshold {
    public int limit = 0;
    public int windowSeconds = 0;
  }

  public static final class Compatibility {
    public ServerBridgeCompatibility serverBridge = new ServerBridgeCompatibility();
    public CraftyCompatibility crafty = new CraftyCompatibility();
  }

  public static final class ServerBridgeCompatibility {
    public boolean enabled = false;
  }

  public static final class CraftyCompatibility {
    /**
     * When true, an external manager (Crafty Controller) owns the backend processes.
     * ServerManager then stops starting/stopping/restarting backends and determines whether a
     * server is "up" by pinging it, instead of by a process handle it owns. Whitelist, the login
     * webpage, bans/moderation, MOTD and forced-host routing are unaffected.
     */
    public boolean enabled = false;
  }

  public static Config loadOrCreateDefault(Path path, Logger log) throws Exception {
    if (!Files.exists(path)) {
      final String defaultYaml = """
        kickMessage: "Server Starting"
        startupGraceSeconds: 15
        stopGraceSeconds: 60
        reconnectWindowSeconds: 60
        motd:
          offline:  "<gray>Your Network</gray> <gray>- <white><server></white></gray>"
          offline2: "<red><bold>Server Offline - Join to Start</bold></red>"
          starting: "<yellow><bold>Server Starting</bold></yellow> <white><server></white>"
          starting2: "<gray>Please wait...</gray>"
          online:   "<gray>Your Network</gray> <gray>- <white><server></white></gray>"
          online2:  "<green><bold>Server Online</bold></green>"
        serverMenu:
          # Reskins /server (replaces Velocity's built-in). All values are MiniMessage.
          # enabled toggle needs a proxy restart; everything else is live on /sm reload.
          enabled: true
          header:  "<gradient:#00d2ff:#3a7bd5><bold>Network Servers</bold></gradient>"
          footer:  "<gray><italic>Hover a server to see who's on. Click to join.</italic></gray>"
          entry:   "  <gray>▸</gray> <aqua><server></aqua> <dark_gray>(<count> online)</dark_gray> <version> <state>"
          # <version> is filled from the backend's last ping (blank when offline/unknown).
          version: "<dark_gray>[<version>]</dark_gray>"
          stateOnline:  "<green>●</green>"
          stateOffline: "<red>●</red>"
          tooltipHeader: "<aqua><bold><server></bold></aqua> <gray>— <count> online</gray>"
          tooltipPlayer: "<white>• <player></white>"
          tooltipEmpty:  "<dark_gray><italic>(empty)</italic></dark_gray>"
          tooltipMore:   "<dark_gray><italic>…and <count> more</italic></dark_gray>"
          tooltipMaxPlayers: 15
          showOffline: true
        messages:
          startingQueued: "<yellow>Starting <white><server></white>… You'll be sent automatically.</yellow>"
          startFailed:    "<red>Failed to start <white><server></white>. Try again.</red>"
          readySending:   "<green><white><server></white> is ready. Sending you now…</green>"
          timeout:        "<red><white><server></white> didn't come up in time.</red>"
          unknownServer:  "<red>Unknown server <white><server></white>.</red>"
          noPermission:   "<red>You don't have permission.</red>"
          usage:          "<gray>Usage:</gray> <white>/sm <green>start</green>|<green>stop</green>|<green>status</green>|<green>hold</green>|<green>updateplugins</green>|<green>preference</green> ...</white>"
          helpHeader:     "<gray>ServerManager commands:</gray>"
          holdUsage:      "<gray>Usage:</gray> <white>/sm hold <green><server></green> <green><duration|forever|clear></green></white>"
          updatePluginsUsage: "<gray>Usage:</gray> <white>/sm updateplugins <green><server></green> <green><waiting></green></white>"
          updatePluginsBusy: "<yellow>Another plugin update workflow is already running.</yellow>"
          updatePluginsPreparing: "<yellow>Starting <white><waiting></white> before restarting <white><server></white>...</yellow>"
          updatePluginsMoving: "<yellow><white><waiting></white> is ready. Moving all players before restarting <white><server></white>...</yellow>"
          updatePluginsRestarting: "<yellow>Restarting <white><server></white> now...</yellow>"
          updatePluginsReturning: "<green><white><server></white> is back online. Sending players there now...</green>"
          updatePluginsComplete: "<green>Plugin update flow complete. Players were returned to <white><server></white>.</green>"
          updatePluginsFailed: "<red>Plugin update flow failed: <reason></red>"
          updatePluginsSameServer: "<red><white><server></white> and <white><waiting></white> must be different servers.</red>"
          updatePluginsPlayerWaiting: "<yellow>Plugin updates are in progress. Sending you to <white><waiting></white>...</yellow>"
          updatePluginsPlayerReturning: "<green>Plugin updates finished. Sending you to <white><server></white>...</green>"
          holdSet:        "<green><white><server></white> will stay online for the next <duration>.</green>"
          holdStatus:     "<gray><white><server></white> hold remaining: <duration>.</gray>"
          holdCleared:    "<yellow>Hold cleared for <white><server></white>.</yellow>"
          holdNotActive:  "<gray><white><server></white> is not currently held.</gray>"
          holdInvalidDuration: "<red>Unknown duration '<duration>'.</red>"
          holdStatusSuffix: "<gray>(hold: <duration>)</gray>"
          holdRestartWarning1m: "<yellow><white><server></white> will restart in 1 minute.</yellow>"
          holdRestartWarning5s: "<red><white><server></white> will restart in 5 seconds.</red>"
          holdRestartNow: "<red><white><server></white> is restarting now...</red>"
          reloadSuccess:  "<green>ServerManager reloaded successfully.</green>"
          reloadFailed:   "<red>Reload failed. Check console for details.</red>"
          notWhitelistedBackend: "<red>You are not whitelisted on <white><server></white>.</red>"
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
          stealthBanKickMessages:
            - "Internal Exception: java.net.SocketException: Connection reset"
            - "Disconnected"
            - "Failed to connect to server"
            - "Invalid Session (Try restarting your game)"
            - "Failed to verify username!"
            - "Connection lost"
            - "Timed out"
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
            openConsoleWindow: false
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
            openConsoleWindow: false
        whitelist:
          enabled: false
          bind: "127.0.0.1"
          port: 8081
          baseUrl: "http://127.0.0.1:8081"
          kickMessage: "You are not whitelisted. Join our Discord ( <url> ) and run /link <code>"
          codeLength: 6
          codeTtlSeconds: 900
          dataFile: "network-whitelist.yml"
          allowVanillaBypass: true
          pageTitle: "Network Access"
          pageSubtitle: "Enter the code shown in-game to whitelist your account."
          successMessage: "Success! You are now whitelisted. You may rejoin the server."
          failureMessage: "Invalid or expired code. Rejoin in-game to get a new one."
          buttonText: "Verify & Whitelist"
          maxAttempts: 10
          windowSeconds: 300
          rateLimitedMessage: "Too many attempts. Please wait a few minutes and try again."
        maps:
          enabled: false
          bind: "127.0.0.1"
          port: 8090
          pageTitle: "Merp Network Maps"
          tabs:
            - { label: "SMP",  path: "smp",  backend: "http://127.0.0.1:8124" }
            - { label: "SMP2", path: "smp2", backend: "http://127.0.0.1:8123" }
        discord:
          enabled: false
          guildId: ""
          invite: ""
          linkSuccess: "Linked! You are now whitelisted — rejoin the Minecraft server."
          linkFailure: "That code is invalid or expired. Rejoin Minecraft to get a fresh code."
        moderation:
          enabled: true
          dataFile: "moderation.yml"
        autoIpBan:
          enabled: false
          dryRun: false
          banMinutes: 60
          reasonPrefix: "Auto-ban"
          trustedIps: []
          trustedCidrs: []
          thresholds:
            connections: { limit: 10, windowSeconds: 10 }
            pings:       { limit: 15, windowSeconds: 10 }
            badUsernames: { limit: 5,  windowSeconds: 60 }
        compatibility:
          serverBridge:
            enabled: false
          crafty:
            # true => Crafty Controller owns the backend processes. ServerManager will not
            # start/stop/restart backends and decides "online" by pinging them. Whitelist,
            # login webpage, bans/moderation, MOTD and forced-host routing keep working.
            enabled: false
        forcedHosts: {}
        """;
      Files.createDirectories(path.getParent());
      Files.writeString(path, defaultYaml, StandardCharsets.UTF_8);
      log.info("Wrote default config to {}", path.toString().replace('\\','/'));
    }

    // Load with plain SnakeYAML; strip any legacy "!!" header if present.
    Yaml yaml = new Yaml();
    String raw = Files.readString(path, StandardCharsets.UTF_8);
    String trimmed = raw.stripLeading();
    if (trimmed.startsWith("!!")) {
      int nl = raw.indexOf('\n');
      String cleaned = (nl >= 0) ? raw.substring(nl + 1) : "";
      Files.writeString(path, cleaned, StandardCharsets.UTF_8);
      log.warn("Removed legacy YAML tag header from {}", path.getFileName());
      raw = cleaned;
    }

    Config cfg = yaml.loadAs(raw, Config.class);
    if (cfg == null) throw new IllegalStateException("Config is empty or invalid: " + path);

    if (cfg.servers == null || cfg.servers.isEmpty())
      throw new IllegalStateException("Define at least one server under 'servers:' in config.yml");

    if (cfg.primaryServerName() == null)
      throw new IllegalStateException("Exactly one server must have startOnJoin: true");

    if (cfg.motd == null) cfg.motd = new Motd();
    if (cfg.serverMenu == null) cfg.serverMenu = new ServerMenu();
    if (cfg.messages == null) cfg.messages = new Messages();
    if (cfg.messages.stealthBanKickMessages == null || cfg.messages.stealthBanKickMessages.isEmpty()) {
      cfg.messages.stealthBanKickMessages = new ArrayList<>(Messages.defaultStealthBanKickMessages());
    }
    if (cfg.whitelist == null) cfg.whitelist = new Whitelist();
    if (cfg.maps == null) cfg.maps = new Maps();
    if (cfg.maps.tabs == null) cfg.maps.tabs = new ArrayList<>();
    if (cfg.moderation == null) cfg.moderation = new Moderation();
    if (cfg.autoIpBan == null) cfg.autoIpBan = new AutoIpBan();
    if (cfg.autoIpBan.thresholds == null) cfg.autoIpBan.thresholds = new AutoThresholds();
    if (cfg.autoIpBan.thresholds.connections == null) cfg.autoIpBan.thresholds.connections = new Threshold();
    if (cfg.autoIpBan.thresholds.pings == null) cfg.autoIpBan.thresholds.pings = new Threshold();
    if (cfg.autoIpBan.thresholds.badUsernames == null) cfg.autoIpBan.thresholds.badUsernames = new Threshold();
    if (cfg.compatibility == null) cfg.compatibility = new Compatibility();
    if (cfg.compatibility.serverBridge == null) cfg.compatibility.serverBridge = new ServerBridgeCompatibility();
    if (cfg.compatibility.crafty == null) cfg.compatibility.crafty = new CraftyCompatibility();
    if (cfg.discord == null) cfg.discord = new Discord();
    if (cfg.forcedHosts == null) cfg.forcedHosts = new LinkedHashMap<>();

    if (cfg.startupGraceSeconds < 0) cfg.startupGraceSeconds = 0;
    if (cfg.stopGraceSeconds < 0) cfg.stopGraceSeconds = 0;
    if (cfg.reconnectWindowSeconds < 0) cfg.reconnectWindowSeconds = 0;
    if (cfg.whitelist.codeLength < 4) cfg.whitelist.codeLength = 4;
    if (cfg.whitelist.codeTtlSeconds < 60) cfg.whitelist.codeTtlSeconds = 60;
    if (cfg.whitelist.maxAttempts < 1) cfg.whitelist.maxAttempts = 1;
    if (cfg.whitelist.windowSeconds < 1) cfg.whitelist.windowSeconds = 1;

    return cfg;
  }

  public String primaryServerName() {
    String primary = null;
    for (var e : servers.entrySet()) {
      if (Boolean.TRUE.equals(e.getValue().startOnJoin)) {
        if (primary != null) return null; // more than one -> invalid
        primary = e.getKey();
      }
    }
    return primary;
  }
}
