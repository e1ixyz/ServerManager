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
  public Messages messages = new Messages();

  public static final class Motd {
    public String offline  = "<gray>eli server :3</gray>";
    public String offline2 = "<red><bold>Server Offline - Join to Start</bold></red>";
    public String starting = "<yellow><bold>Server Starting</bold></yellow>";
    public String starting2 = "<gray>Please wait...</gray>";
    public String online   = "<gray>eli server :3</gray>";
    public String online2  = "<green><bold>Server Online</bold></green>";
  }

  public static final class Messages {
    public String startingQueued = "<yellow>Starting <white><server></white>… You’ll be sent automatically.</yellow>";
    public String startFailed    = "<red>Failed to start <white><server></white>. Try again.</red>";
    public String readySending   = "<green><white><server></white> is ready. Sending you now…</green>";
    public String timeout        = "<red><white><server></white> didn’t come up in time.</red>";
    public String unknownServer  = "<red>Unknown server <white><server></white>.</red>";
    public String noPermission   = "<red>You don’t have permission.</red>";
    public String usage          = "<gray>Usage:</gray> <white>/sm <green>start</green>|<green>stop</green>|<green>status</green> [server]</white>";
    public String started        = "<green>Started <white><server></white>.</green>";
    public String alreadyRunning = "<yellow><white><server></white> is already running.</yellow>";
    public String stopped        = "<yellow>Stopped <white><server></white>.</yellow>";
    public String alreadyStopped = "<gray><white><server></white> is not running.</gray>";
    public String statusHeader   = "<gray>Managed servers:</gray>";
    public String statusLine     = "<white><server></white>: <state>";
    public String stateOnline    = "<green>online</green>";
    public String stateOffline   = "<red>offline</red>";
  }

  public static Config loadOrCreateDefault(Path path, Logger log) throws Exception {
    if (!Files.exists(path)) {
      final String defaultYaml = """
        kickMessage: "Server Starting"
        startupGraceSeconds: 15
        stopGraceSeconds: 60
        motd:
          offline:  "<gray>eli server :3</gray>"
          offline2: "<red><bold>Server Offline - Join to Start</bold></red>"
          starting: "<yellow><bold>Server Starting</bold></yellow>"
          starting2: "<gray>Please wait...</gray>"
          online:   "<gray>eli server :3</gray>"
          online2:  "<green><bold>Server Online</bold></green>"
        messages:
          startingQueued: "<yellow>Starting <white><server></white>… You’ll be sent automatically.</yellow>"
          startFailed:    "<red>Failed to start <white><server></white>. Try again.</red>"
          readySending:   "<green><white><server></white> is ready. Sending you now…</green>"
          timeout:        "<red><white><server></white> didn’t come up in time.</red>"
          unknownServer:  "<red>Unknown server <white><server></white>.</red>"
          noPermission:   "<red>You don’t have permission.</red>"
          usage:          "<gray>Usage:</gray> <white>/sm <green>start</green>|<green>stop</green>|<green>status</green> [server]</white>"
          started:        "<green>Started <white><server></white>.</green>"
          alreadyRunning: "<yellow><white><server></white> is already running.</yellow>"
          stopped:        "<yellow>Stopped <white><server></white>.</yellow>"
          alreadyStopped: "<gray><white><server></white> is not running.</gray>"
          statusHeader:   "<gray>Managed servers:</gray>"
          statusLine:     "<white><server></white>: <state>"
          stateOnline:    "<green>online</green>"
          stateOffline:   "<red>offline</red>"
        servers:
          testing:
            startOnJoin: true
            workingDir: "../testing-1.21.8"
            startCommand: "java -Xms4096M -Xmx4096M -jar paper-1.21.8-49.jar nogui"
            stopCommand: "stop"
            logToFile: true
            logFile: "logs/proxy-managed-testing.log"
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

    if (cfg.startupGraceSeconds < 0) cfg.startupGraceSeconds = 0;
    if (cfg.stopGraceSeconds < 0) cfg.stopGraceSeconds = 0;

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
