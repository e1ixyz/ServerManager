package com.example.soj.commands;

import com.example.soj.Config;
import com.example.soj.ServerManagerPlugin;
import com.example.soj.ServerProcessManager;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ServerManagerCmd implements SimpleCommand {
  private static final String[] WILDCARD_PERMS = {
      "servermanager.command.*",
      "servermanager.*",
      "startonjoin.*"
  };
  private static final Pattern DURATION_TOKEN = Pattern.compile("(?i)(\\d+)([dhms]?)");
  private volatile ServerProcessManager mgr;
  private volatile Config cfg;
  private final Logger log;
  private final ServerManagerPlugin plugin;

  public ServerManagerCmd(ServerManagerPlugin plugin, ServerProcessManager mgr, Config cfg, Logger log) {
    this.plugin = plugin;
    this.log = log;
    updateState(mgr, cfg);
  }

  public void updateState(ServerProcessManager mgr, Config cfg) {
    this.mgr = mgr;
    this.cfg = cfg;
  }

  @Override
  public void execute(Invocation inv) {
    var src = inv.source();
    var args = inv.arguments();
    ServerProcessManager manager = this.mgr;
    Config config = this.cfg;

    if (manager == null || config == null) {
      src.sendMessage(Component.text("ServerManager is not ready."));
      return;
    }

    if (args.length == 0) {
      src.sendMessage(mm0(config.messages.usage));
      return;
    }

    String sub = args[0].toLowerCase(Locale.ROOT);
    String server = (args.length >= 2 ? args[1] : null);

    switch (sub) {
      case "status" -> {
        if (!has(src, "servermanager.command.status", "servermanager.status")) { src.sendMessage(mm0(config.messages.noPermission)); return; }
        src.sendMessage(mm0(config.messages.statusHeader));
        for (var name : config.servers.keySet()) {
          boolean running = manager.isRunning(name);
          String state = running ? config.messages.stateOnline : config.messages.stateOffline;
          src.sendMessage(mmState(config.messages.statusLine, name, state));
        }
      }
      case "start" -> {
        if (!has(src, "servermanager.command.start", "servermanager.start")) { src.sendMessage(mm0(config.messages.noPermission)); return; }
        if (server == null) { src.sendMessage(mm0(config.messages.usage)); return; }
        if (!manager.isKnown(server)) { src.sendMessage(mm2(config.messages.unknownServer, server, nameOf(src))); return; }
        if (manager.isRunning(server)) { src.sendMessage(mm2(config.messages.alreadyRunning, server, nameOf(src))); return; }
        try {
          manager.start(server);
          src.sendMessage(mm2(config.messages.started, server, nameOf(src)));
        } catch (IOException ex) {
          log.error("Failed to start {}", server, ex);
          src.sendMessage(mm2(config.messages.startFailed, server, nameOf(src)));
        }
      }
      case "stop" -> {
        if (!has(src, "servermanager.command.stop", "servermanager.stop")) { src.sendMessage(mm0(config.messages.noPermission)); return; }
        if (server == null) { src.sendMessage(mm0(config.messages.usage)); return; }
        if (!manager.isKnown(server)) { src.sendMessage(mm2(config.messages.unknownServer, server, nameOf(src))); return; }
        if (!manager.isRunning(server)) { src.sendMessage(mm2(config.messages.alreadyStopped, server, nameOf(src))); return; }
        manager.stop(server);
        src.sendMessage(mm2(config.messages.stopped, server, nameOf(src)));
      }
      case "hold" -> {
        if (!has(src, "servermanager.command.hold", "servermanager.hold")) {
          src.sendMessage(mm0(config.messages.noPermission));
          return;
        }
        if (server == null) {
          src.sendMessage(mm0(firstNonBlank(config.messages.holdUsage, config.messages.usage)));
          return;
        }
        if (!manager.isKnown(server)) {
          src.sendMessage(mm2(config.messages.unknownServer, server, nameOf(src)));
          return;
        }

        var playerName = nameOf(src);
        if (args.length == 2) {
          long remaining = manager.holdRemainingSeconds(server);
          if (remaining <= 0) {
            src.sendMessage(mmDuration(firstNonBlank(config.messages.holdNotActive, config.messages.holdStatus), server, playerName, formatDuration(0)));
          } else {
            src.sendMessage(mmDuration(firstNonBlank(config.messages.holdStatus, config.messages.started), server, playerName, formatDuration(remaining)));
          }
          return;
        }

        String option = args[2];
        if (equalsIgnoreCase(option, "clear", "cancel", "off", "remove")) {
          boolean cleared = manager.clearHold(server);
          if (cleared) {
            src.sendMessage(mmDuration(firstNonBlank(config.messages.holdCleared, config.messages.holdStatus), server, playerName, ""));
          } else {
            src.sendMessage(mmDuration(firstNonBlank(config.messages.holdNotActive, config.messages.holdStatus), server, playerName, formatDuration(0)));
          }
          return;
        }

        StringBuilder rawBuilder = new StringBuilder();
        for (int i = 2; i < args.length; i++) {
          if (i > 2) rawBuilder.append(' ');
          rawBuilder.append(args[i]);
        }
        String rawDuration = rawBuilder.toString();

        long seconds = parseDurationSeconds(rawDuration);
        if (seconds <= 0) {
          String shown = rawDuration.isBlank() ? option : rawDuration;
          src.sendMessage(mmDuration(firstNonBlank(config.messages.holdInvalidDuration, config.messages.usage), server, playerName, shown));
          return;
        }

        boolean wasRunning = manager.isRunning(server);
        if (!wasRunning) {
          try {
            manager.start(server);
            src.sendMessage(mm2(config.messages.started, server, playerName));
          } catch (IOException ex) {
            log.error("Failed to start {} for hold command", server, ex);
            src.sendMessage(mm2(config.messages.startFailed, server, playerName));
            return;
          }
        }

        manager.hold(server, seconds);
        long remaining = manager.holdRemainingSeconds(server);
        src.sendMessage(mmDuration(firstNonBlank(config.messages.holdSet, config.messages.holdStatus), server, playerName, formatDuration(remaining)));
      }
      case "reload" -> {
        if (!has(src, "servermanager.command.reload", "servermanager.reload")) { src.sendMessage(mm0(config.messages.noPermission)); return; }
        Config previous = config;
        boolean ok = plugin.reload();
        Config after = this.cfg != null ? this.cfg : previous;
        Config.Messages messages = after != null ? after.messages : (previous != null ? previous.messages : null);
        String msg = ok
            ? (messages != null && messages.reloadSuccess != null ? messages.reloadSuccess : "<green>ServerManager reloaded.</green>")
            : (messages != null && messages.reloadFailed != null ? messages.reloadFailed : "<red>Reload failed.</red>");
        src.sendMessage(mm0(msg));
      }
      default -> src.sendMessage(mm0(config.messages.usage));
    }
  }

  private static boolean has(com.velocitypowered.api.command.CommandSource src, String... perms) {
    if (!(src instanceof Player)) return true; // console and other non-player sources bypass checks
    for (String perm : perms) {
      if (perm != null && src.hasPermission(perm)) return true;
    }
    for (String wildcard : WILDCARD_PERMS) {
      if (src.hasPermission(wildcard)) return true;
    }
    return false;
  }

  private static String nameOf(com.velocitypowered.api.command.CommandSource src) {
    return (src instanceof Player p) ? p.getUsername() : "CONSOLE";
  }

  private static boolean equalsIgnoreCase(String value, String... candidates) {
    if (value == null) return false;
    for (String c : candidates) {
      if (c != null && value.equalsIgnoreCase(c)) return true;
    }
    return false;
  }

  private static String firstNonBlank(String... values) {
    if (values == null) return "";
    for (String v : values) {
      if (v != null && !v.isBlank()) return v;
    }
    return "";
  }

  // ----- MiniMessage helpers (normalize {server}/(server)/<server> etc.) -----
  private static String normalize(String s) {
    if (s == null) return "";
    return s
        .replace("{server}", "<server>").replace("(server)", "<server>")
        .replace("{player}", "<player>").replace("(player)", "<player>")
        .replace("{state}", "<state>").replace("(state)", "<state>")
        .replace("{duration}", "<duration>").replace("(duration)", "<duration>");
  }

  private static Component mm0(String template) {
    return MiniMessage.miniMessage().deserialize(normalize(template));
  }

  private static Component mm2(String template, String server, String player) {
    return MiniMessage.miniMessage().deserialize(normalize(template),
        Placeholder.unparsed("server", server == null ? "" : server),
        Placeholder.unparsed("player", player == null ? "" : player));
  }

  private static Component mmState(String template, String server, String state) {
    return MiniMessage.miniMessage().deserialize(normalize(template),
        Placeholder.unparsed("server", server == null ? "" : server),
        // 'state' may itself contain MiniMessage markup (e.g., <green>online</green>)
        Placeholder.parsed("state", state == null ? "" : state));
  }

  private static Component mmDuration(String template, String server, String player, String duration) {
    return MiniMessage.miniMessage().deserialize(normalize(template),
        Placeholder.unparsed("server", server == null ? "" : server),
        Placeholder.unparsed("player", player == null ? "" : player),
        Placeholder.unparsed("duration", duration == null ? "" : duration));
  }

  private static long parseDurationSeconds(String raw) {
    if (raw == null) return -1;
    String trimmed = raw.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    if (trimmed.isEmpty()) return -1;

    Matcher matcher = DURATION_TOKEN.matcher(trimmed);
    int index = 0;
    long total = 0L;
    while (matcher.find()) {
      if (matcher.start() != index) return -1;
      index = matcher.end();

      long value = Long.parseLong(matcher.group(1));
      String unitGroup = matcher.group(2);
      char unit = unitGroup == null || unitGroup.isEmpty() ? 'm' : unitGroup.charAt(0);
      long multiplier = switch (Character.toLowerCase(unit)) {
        case 'd' -> 86400L;
        case 'h' -> 3600L;
        case 'm' -> 60L;
        case 's' -> 1L;
        default -> -1L;
      };
      if (multiplier <= 0L) return -1;

      if (value > Long.MAX_VALUE / multiplier) {
        total = Long.MAX_VALUE;
        continue;
      }
      long add = value * multiplier;
      if (Long.MAX_VALUE - total < add) {
        total = Long.MAX_VALUE;
      } else {
        total += add;
      }
    }

    if (index != trimmed.length()) return -1;
    if (total <= 0L) return -1;
    return total;
  }

  private static String formatDuration(long seconds) {
    if (seconds <= 0L) return "0s";
    long remaining = seconds;
    long days = remaining / 86400L;
    remaining %= 86400L;
    long hours = remaining / 3600L;
    remaining %= 3600L;
    long minutes = remaining / 60L;
    long secs = remaining % 60L;

    StringBuilder sb = new StringBuilder();
    appendUnit(sb, days, 'd');
    appendUnit(sb, hours, 'h');
    appendUnit(sb, minutes, 'm');
    if (sb.length() == 0 || days == 0 && hours == 0 && minutes == 0) {
      appendUnit(sb, secs, 's');
    } else if (secs > 0) {
      appendUnit(sb, secs, 's');
    }
    return sb.toString().trim();
  }

  private static void appendUnit(StringBuilder sb, long value, char suffix) {
    if (value <= 0) return;
    if (sb.length() > 0) sb.append(' ');
    sb.append(value).append(suffix);
  }
}
