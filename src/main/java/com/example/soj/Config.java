package com.example.soj;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Config {
  public String kickMessage = "Server Starting";
  public int startupGraceSeconds = 15;
  public int stopGraceSeconds = 60;

  public Motd motd = new Motd();
  public Map<String, ServerConfig> servers = new LinkedHashMap<>();
  /** Optional forced-host overrides keyed by hostname (case-insensitive). */
  public Map<String, ForcedHost> forcedHosts = new LinkedHashMap<>();
  public Messages messages = new Messages();
  public Whitelist whitelist = new Whitelist();
  public Banlist banlist = new Banlist();

  public static final class Motd {
    public String offline  = "<gray>Your Network</gray> <gray>- <white><server></white></gray>";
    public String offline2 = "<red><bold>Server Offline - Join to Start</bold></red>";
    public String starting = "<yellow><bold>Server Starting</bold></yellow> <white><server></white>";
    public String starting2 = "<gray>Please wait...</gray>";
    public String online   = "<gray>Your Network</gray> <gray>- <white><server></white></gray>";
    public String online2  = "<green><bold>Server Online</bold></green>";
  }

  public static final class Messages {
    public String startingQueued = "<yellow>Starting <white><server></white>… You’ll be sent automatically.</yellow>";
    public String startFailed    = "<red>Failed to start <white><server></white>. Try again.</red>";
    public String readySending   = "<green><white><server></white> is ready. Sending you now…</green>";
    public String timeout        = "<red><white><server></white> didn’t come up in time.</red>";
    public String unknownServer  = "<red>Unknown server <white><server></white>.</red>";
    public String noPermission   = "<red>You don’t have permission.</red>";
    public String usage          = "<gray>Usage:</gray> <white>/sm <green>start</green>|<green>stop</green>|<green>status</green>|<green>hold</green> [server] [duration]</white>";
    public String helpHeader     = "<gray>ServerManager commands:</gray>";
    public String holdUsage      = "<gray>Usage:</gray> <white>/sm hold <green><server></green> <green><duration|clear></green></white>";
    public String holdSet        = "<green><white><server></white> will stay online for the next <duration>.</green>";
    public String holdStatus     = "<gray><white><server></white> hold remaining: <duration>.</gray>";
    public String holdCleared    = "<yellow>Hold cleared for <white><server></white>.</yellow>";
    public String holdNotActive  = "<gray><white><server></white> is not currently held.</gray>";
    public String holdInvalidDuration = "<red>Unknown duration '<duration>'.</red>";
    public String holdStatusSuffix = "<gray>(hold: <duration>)</gray>";
    public String reloadSuccess  = "<green>ServerManager reloaded successfully.</green>";
    public String reloadFailed   = "<red>Reload failed. Check console for details.</red>";
    public String notWhitelistedBackend = "<red>You are not whitelisted on <white><server></white>.</red>";
    public String started        = "<green>Started <white><server></white>.</green>";
    public String alreadyRunning = "<yellow><white><server></white> is already running.</yellow>";
    public String stopped        = "<yellow>Stopped <white><server></white>.</yellow>";
    public String alreadyStopped = "<gray><white><server></white> is not running.</gray>";
    public String statusHeader   = "<gray>Managed servers:</gray>";
    public String statusLine     = "<white><server></white>: <state>";
    public String stateOnline    = "<green>online</green>";
    public String stateOffline   = "<red>offline</red>";
    public String networkBanned  = "<red>You are banned from this network.</red><newline><gray>Reason: <reason></gray>";
  }

  public static final class Whitelist {
    public boolean enabled = false;
    public String bind = "0.0.0.0";
    public int port = 8081;
    public String baseUrl = "http://127.0.0.1:8081";
    public String kickMessage = "You are not whitelisted. Visit <url> and enter your username and code <code>.";
    public int codeLength = 6;
    public int codeTtlSeconds = 900;
    public String dataFile = "network-whitelist.yml";
    public boolean allowVanillaBypass = true;
    public String pageTitle = "Network Access";
    public String pageSubtitle = "Enter the code shown in-game to whitelist your account.";
    public String successMessage = "Success! You are now whitelisted. You may rejoin the server.";
    public String failureMessage = "Invalid or expired code. Please try again from in-game.";
    public String buttonText = "Verify & Whitelist";
  }

  public static final class ForcedHost {
    /** Managed server name Velocity routes this host to. */
    public String server;
    /** Optional MOTD overrides for this host; falls back to global MOTD when null. */
    public Motd motd;
    /** Optional kick message when start-on-join kicks the player. */
    public String kickMessage;
  }

  public static final class Banlist {
    public boolean enabled = true;
    public String dataFile = "network-bans.yml";
  }

  public static Config loadOrCreateDefault(Path path, Logger log) throws Exception {
    if (!Files.exists(path)) {
      final String defaultYaml = """
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
          startingQueued: "<yellow>Starting <white><server></white>… You'll be sent automatically.</yellow>"
          startFailed:    "<red>Failed to start <white><server></white>. Try again.</red>"
          readySending:   "<green><white><server></white> is ready. Sending you now…</green>"
          timeout:        "<red><white><server></white> didn't come up in time.</red>"
          unknownServer:  "<red>Unknown server <white><server></white>.</red>"
          noPermission:   "<red>You don't have permission.</red>"
          usage:          "<gray>Usage:</gray> <white>/sm <green>start</green>|<green>stop</green>|<green>status</green>|<green>hold</green> [server] [duration]</white>"
          helpHeader:     "<gray>ServerManager commands:</gray>"
          holdUsage:      "<gray>Usage:</gray> <white>/sm hold <green><server></green> <green><duration|clear></green></white>"
          holdSet:        "<green><white><server></white> will stay online for the next <duration>.</green>"
          holdStatus:     "<gray><white><server></white> hold remaining: <duration>.</gray>"
          holdCleared:    "<yellow>Hold cleared for <white><server></white>.</yellow>"
          holdNotActive:  "<gray><white><server></white> is not currently held.</gray>"
          holdInvalidDuration: "<red>Unknown duration '<duration>'.</red>"
          holdStatusSuffix: "<gray>(hold: <duration>)</gray>"
          reloadSuccess:  "<green>ServerManager reloaded successfully.</green>"
          reloadFailed:   "<red>Reload failed. Check console for details.</red>"
          notWhitelistedBackend: "<red>You are not whitelisted on <white><server></white>.</red>"
          started:        "<green>Started <white><server></white>.</green>"
          alreadyRunning: "<yellow><white><server></white> is already running.</yellow>"
          stopped:        "<yellow>Stopped <white><server></white>.</yellow>"
          alreadyStopped: "<gray><white><server></white> is not running.</gray>"
          statusHeader:   "<gray>Managed servers:</gray>"
          statusLine:     "<white><server></white>: <state>"
          stateOnline:    "<green>online</green>"
          stateOffline:   "<red>offline</red>"
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
          survival:
            startOnJoin: false
            workingDir: "../survival"
            startCommand: "./start.sh"
            stopCommand: "stop"
            logToFile: true
            logFile: "logs/proxy-managed-survival.log"
            vanillaWhitelistBypassesNetwork: false
            mirrorNetworkWhitelist: false
        whitelist:
          enabled: false
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
        banlist:
          enabled: true
          dataFile: "network-bans.yml"
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
    if (cfg.messages == null) cfg.messages = new Messages();
    if (cfg.whitelist == null) cfg.whitelist = new Whitelist();
    if (cfg.banlist == null) cfg.banlist = new Banlist();
    if (cfg.forcedHosts == null) cfg.forcedHosts = new LinkedHashMap<>();

    if (cfg.startupGraceSeconds < 0) cfg.startupGraceSeconds = 0;
    if (cfg.stopGraceSeconds < 0) cfg.stopGraceSeconds = 0;
    if (cfg.whitelist.codeLength < 4) cfg.whitelist.codeLength = 4;
    if (cfg.whitelist.codeTtlSeconds < 60) cfg.whitelist.codeTtlSeconds = 60;

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
