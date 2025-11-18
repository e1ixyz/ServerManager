package com.example.soj.commands;

import com.example.soj.Config;
import com.example.soj.ServerManagerPlugin;
import com.example.soj.ServerProcessManager;
import com.example.soj.bans.NetworkBanService;
import com.example.soj.whitelist.VanillaWhitelistChecker;
import com.example.soj.whitelist.WhitelistService;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ServerManagerCmd implements SimpleCommand {
  private static final String[] WILDCARD_PERMS = {
      "servermanager.command.*",
      "servermanager.*",
      "startonjoin.*"
  };
  private static final Pattern DURATION_TOKEN = Pattern.compile("(?i)(\\d+)([dhms]?)");
  private static final String WL_USAGE = "<gray>Usage:</gray> <white>/sm whitelist <green>network</green>|<green>vanilla</green> …</white>";
  private static final String WL_NETWORK_USAGE = "<gray>Usage:</gray> <white>/sm whitelist network <green>list</green>|<green>add</green>|<green>remove</green> …</white>";
  private static final String WL_VANILLA_USAGE = "<gray>Usage:</gray> <white>/sm whitelist vanilla <server> <green>list</green>|<green>add</green>|<green>remove</green> …</white>";
  private static final String NETBAN_USAGE = "<gray>Usage:</gray> <white>/sm networkban <green>list</green>|<green>add</green>|<green>remove</green> …</white>";
  private volatile ServerProcessManager mgr;
  private volatile Config cfg;
  private volatile WhitelistService whitelist;
  private volatile VanillaWhitelistChecker vanillaWhitelist;
  private volatile NetworkBanService networkBans;
  private final Logger log;
  private final ServerManagerPlugin plugin;
  private final ProxyServer proxy;

  public ServerManagerCmd(ServerManagerPlugin plugin, ProxyServer proxy, Logger log) {
    this.plugin = plugin;
    this.proxy = proxy;
    this.log = log;
  }

  private void handleWhitelistCommand(CommandSource src, String[] args) {
    if (args.length == 0) {
      src.sendMessage(mm0(WL_USAGE));
      return;
    }
    String scope = args[0].toLowerCase(Locale.ROOT);
    switch (scope) {
      case "network" -> handleNetworkWhitelist(src, tail(args, 1));
      case "vanilla" -> handleVanillaWhitelist(src, tail(args, 1));
      default -> src.sendMessage(mm0(WL_USAGE));
    }
  }

  private void handleNetworkWhitelist(CommandSource src, String[] args) {
    if (whitelist == null) {
      src.sendMessage(Component.text("Network whitelist storage is not initialized."));
      return;
    }
    if (args.length == 0) {
      src.sendMessage(mm0(WL_NETWORK_USAGE));
      return;
    }
    String action = args[0].toLowerCase(Locale.ROOT);
    switch (action) {
      case "list" -> {
        List<WhitelistService.Entry> entries = new ArrayList<>(whitelist.snapshot());
        entries.sort(Comparator.comparingLong(WhitelistService.Entry::addedAt).reversed());
        src.sendMessage(Component.text("Network whitelist entries (" + entries.size() + "):"));
        int limit = Math.min(entries.size(), 20);
        for (int i = 0; i < limit; i++) {
          var entry = entries.get(i);
          src.sendMessage(Component.text(" - " + displayName(entry.lastKnownName(), entry.uuid())
              + " (" + entry.uuid() + ")"));
        }
        if (entries.size() > limit) {
          src.sendMessage(Component.text(" …and " + (entries.size() - limit) + " more."));
        }
      }
      case "add" -> {
        if (args.length < 2) {
          src.sendMessage(mm0(WL_NETWORK_USAGE));
          return;
        }
        Target target = resolveUuidTarget(args[1], args.length >= 3 ? args[2] : null);
        if (target == null || target.uuid() == null) {
          src.sendMessage(Component.text("Player must be online or specify a UUID."));
          return;
        }
        try {
          whitelist.add(target.uuid(), target.name());
          plugin.mirrorNetworkWhitelistEntry(target.uuid(), target.name());
        } catch (IOException ex) {
          log.error("Failed to add network whitelist entry", ex);
          src.sendMessage(Component.text("Failed to update whitelist: " + ex.getMessage()));
          return;
        }
        src.sendMessage(Component.text("Added " + displayName(target.name(), target.uuid()) + " to the network whitelist."));
      }
      case "remove", "delete" -> {
        if (args.length < 2) {
          src.sendMessage(mm0(WL_NETWORK_USAGE));
          return;
        }
        UUID uuid = parseUuidFlexible(args[1]);
        String nameHint = uuid == null ? args[1] : null;
        Optional<WhitelistService.Entry> before = whitelist.lookup(uuid, nameHint);
        boolean removed;
        try {
          removed = whitelist.remove(uuid, nameHint);
        } catch (IOException ex) {
          log.error("Failed to remove network whitelist entry", ex);
          src.sendMessage(Component.text("Failed to update whitelist: " + ex.getMessage()));
          return;
        }
        if (!removed) {
          src.sendMessage(Component.text("No matching whitelist entry."));
          return;
        }
        String finalName = before.map(WhitelistService.Entry::lastKnownName).orElse(nameHint);
        plugin.removeNetworkWhitelistEntry(uuid, finalName);
        src.sendMessage(Component.text("Removed " + displayName(finalName, uuid) + " from the network whitelist."));
      }
      default -> src.sendMessage(mm0(WL_NETWORK_USAGE));
    }
  }

  private void handleVanillaWhitelist(CommandSource src, String[] args) {
    if (vanillaWhitelist == null || !vanillaWhitelist.hasTargets()) {
      src.sendMessage(Component.text("No vanilla whitelist files are configured."));
      return;
    }
    if (args.length < 1) {
      src.sendMessage(mm0(WL_VANILLA_USAGE));
      return;
    }
    String server = args[0];
    if (!vanillaWhitelist.tracksServer(server)) {
      src.sendMessage(Component.text("Unknown managed server: " + server));
      return;
    }
    if (args.length == 1) {
      src.sendMessage(mm0(WL_VANILLA_USAGE));
      return;
    }
    String action = args[1].toLowerCase(Locale.ROOT);
    switch (action) {
      case "list" -> {
        try {
          List<VanillaWhitelistChecker.VanillaEntry> entries = new ArrayList<>(vanillaWhitelist.listEntries(server));
          entries.sort(Comparator.comparing(e -> Objects.toString(e.name(), "")));
          src.sendMessage(Component.text(server + " whitelist entries (" + entries.size() + "):"));
          int limit = Math.min(entries.size(), 20);
          for (int i = 0; i < limit; i++) {
            var entry = entries.get(i);
            src.sendMessage(Component.text(" - " + displayName(entry.name(), entry.uuid())));
          }
          if (entries.size() > limit) {
            src.sendMessage(Component.text(" …and " + (entries.size() - limit) + " more."));
          }
        } catch (IOException ex) {
          log.error("Failed to read vanilla whitelist", ex);
          src.sendMessage(Component.text("Failed to read whitelist: " + ex.getMessage()));
        }
      }
      case "add" -> {
        if (args.length < 3) {
          src.sendMessage(mm0(WL_VANILLA_USAGE));
          return;
        }
        Target target = resolveVanillaTarget(args[2], args.length >= 4 ? args[3] : null);
        if (target == null || (target.uuid() == null && (target.name() == null || target.name().isBlank()))) {
          src.sendMessage(Component.text("Provide a player name or UUID."));
          return;
        }
        try {
          vanillaWhitelist.addEntry(server, target.uuid(), target.name());
        } catch (IOException ex) {
          log.error("Failed to add vanilla whitelist entry", ex);
          src.sendMessage(Component.text("Failed to update whitelist: " + ex.getMessage()));
          return;
        }
        if (target.name() != null && !target.name().isBlank()) {
          sendWhitelistCommand(server, "whitelist add " + target.name());
        }
        src.sendMessage(Component.text("Added " + displayName(target.name(), target.uuid()) + " to " + server + " whitelist."));
      }
      case "remove", "delete" -> {
        if (args.length < 3) {
          src.sendMessage(mm0(WL_VANILLA_USAGE));
          return;
        }
        UUID uuid = parseUuidFlexible(args[2]);
        String name = uuid == null ? args[2] : (args.length >= 4 ? args[3] : null);
        try {
          boolean removed = vanillaWhitelist.removeEntry(server, uuid, name);
          if (!removed) {
            src.sendMessage(Component.text("No matching entry for that player."));
            return;
          }
        } catch (IOException ex) {
          log.error("Failed to remove vanilla whitelist entry", ex);
          src.sendMessage(Component.text("Failed to update whitelist: " + ex.getMessage()));
          return;
        }
        if (name != null && !name.isBlank()) {
          sendWhitelistCommand(server, "whitelist remove " + name);
        }
        src.sendMessage(Component.text("Removed " + displayName(name, uuid) + " from " + server + " whitelist."));
      }
      default -> src.sendMessage(mm0(WL_VANILLA_USAGE));
    }
  }

  private void handleNetworkBanCommand(CommandSource src, String[] args) {
    if (networkBans == null) {
      src.sendMessage(Component.text("Network ban storage is not initialized."));
      return;
    }
    if (args.length == 0) {
      src.sendMessage(mm0(NETBAN_USAGE));
      return;
    }
    String action = args[0].toLowerCase(Locale.ROOT);
    switch (action) {
      case "list" -> {
        List<NetworkBanService.Entry> entries = new ArrayList<>(networkBans.entries());
        entries.sort(Comparator.comparing(NetworkBanService.Entry::bannedAt).reversed());
        src.sendMessage(Component.text("Network bans (" + entries.size() + "):"));
        int limit = Math.min(entries.size(), 20);
        for (int i = 0; i < limit; i++) {
          var entry = entries.get(i);
          String line = " - " + displayName(entry.lastKnownName(), entry.uuid());
          if (entry.reason() != null && !entry.reason().isBlank()) {
            line += " [" + entry.reason() + "]";
          }
          src.sendMessage(Component.text(line));
        }
        if (entries.size() > limit) {
          src.sendMessage(Component.text(" …and " + (entries.size() - limit) + " more."));
        }
      }
      case "add" -> {
        if (args.length < 2) {
          src.sendMessage(mm0(NETBAN_USAGE));
          return;
        }
        Target target = resolveUuidTarget(args[1], args.length >= 3 ? args[2] : null);
        if (target == null || target.uuid() == null) {
          src.sendMessage(Component.text("Player must be online or specify a UUID."));
          return;
        }
        String reason = args.length >= 3 ? joinArgs(args, 2) : "Banned by " + nameOf(src);
        try {
          networkBans.ban(target.uuid(), target.name(), reason, nameOf(src));
        } catch (IOException ex) {
          log.error("Failed to add network ban", ex);
          src.sendMessage(Component.text("Failed to update ban list: " + ex.getMessage()));
          return;
        }
        if (whitelist != null) {
          try {
            whitelist.remove(target.uuid(), target.name());
          } catch (IOException ex) {
            log.warn("Failed to remove {} from whitelist while banning", target.name(), ex);
          }
        }
        plugin.removeNetworkWhitelistEntry(target.uuid(), target.name());
        proxy.getPlayer(target.uuid()).ifPresent(p -> p.disconnect(mmReason(firstNonBlank(cfg.messages.networkBanned, "<red>You are banned.</red>"), p.getUsername(), reason)));
        src.sendMessage(Component.text("Banned " + displayName(target.name(), target.uuid()) + " for: " + reason));
      }
      case "remove", "delete", "unban" -> {
        if (args.length < 2) {
          src.sendMessage(mm0(NETBAN_USAGE));
          return;
        }
        UUID uuid = parseUuidFlexible(args[1]);
        String nameHint = uuid == null ? args[1] : null;
        Optional<NetworkBanService.Entry> before = networkBans.lookup(uuid, nameHint);
        if (before.isEmpty()) {
          src.sendMessage(Component.text("No network ban found for that player."));
          return;
        }
        try {
          networkBans.unban(uuid, nameHint);
        } catch (IOException ex) {
          log.error("Failed to remove network ban", ex);
          src.sendMessage(Component.text("Failed to update ban list: " + ex.getMessage()));
          return;
        }
        src.sendMessage(Component.text("Unbanned " + displayName(before.get().lastKnownName(), before.get().uuid()) + "."));
      }
      default -> src.sendMessage(mm0(NETBAN_USAGE));
    }
  }
  public void updateState(ServerProcessManager mgr, Config cfg,
                          WhitelistService whitelist, VanillaWhitelistChecker vanillaWhitelist,
                          NetworkBanService networkBans) {
    this.mgr = mgr;
    this.cfg = cfg;
    this.whitelist = whitelist;
    this.vanillaWhitelist = vanillaWhitelist;
    this.networkBans = networkBans;
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
          long holdRemaining = manager.holdRemainingSeconds(name);
          if (holdRemaining > 0) {
            String suffix = renderDurationSnippet(config.messages.holdStatusSuffix, holdRemaining);
            if (!suffix.isBlank()) {
              if (!state.isBlank() && !state.endsWith(" ")) state = state + " ";
              state = state + suffix;
            }
          }
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
      case "help" -> {
        if (config.messages.helpHeader != null && !config.messages.helpHeader.isBlank()) {
          src.sendMessage(mm0(config.messages.helpHeader));
        }
        src.sendMessage(mm0(config.messages.usage));
        if (config.messages.holdUsage != null && !config.messages.holdUsage.isBlank()) {
          src.sendMessage(mm0(config.messages.holdUsage));
        }
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
      case "whitelist" -> {
        if (!has(src, "servermanager.command.whitelist", "servermanager.whitelist")) {
          src.sendMessage(mm0(config.messages.noPermission));
          return;
        }
        handleWhitelistCommand(src, tail(args, 1));
      }
      case "networkban", "netban" -> {
        if (!has(src, "servermanager.command.networkban", "servermanager.networkban")) {
          src.sendMessage(mm0(config.messages.noPermission));
          return;
        }
        handleNetworkBanCommand(src, tail(args, 1));
      }
      default -> src.sendMessage(mm0(config.messages.usage));
    }
  }

  private static String[] tail(String[] args, int from) {
    if (args == null || from >= args.length) return new String[0];
    return Arrays.copyOfRange(args, from, args.length);
  }

  private Target resolveUuidTarget(String token, String providedName) {
    UUID uuid = parseUuidFlexible(token);
    if (uuid != null) {
      String name = firstNonBlank(providedName, knownName(uuid));
      return new Target(uuid, name);
    }
    var online = proxy.getPlayer(token);
    if (online.isPresent()) {
      var player = online.get();
      return new Target(player.getUniqueId(), player.getUsername());
    }
    return null;
  }

  private Target resolveVanillaTarget(String token, String providedName) {
    UUID uuid = parseUuidFlexible(token);
    if (uuid != null) {
      String name = firstNonBlank(providedName, knownName(uuid));
      return new Target(uuid, name);
    }
    var online = proxy.getPlayer(token);
    if (online.isPresent()) {
      var player = online.get();
      return new Target(player.getUniqueId(), player.getUsername());
    }
    String name = firstNonBlank(providedName, token);
    return new Target(null, name);
  }

  private UUID parseUuidFlexible(String raw) {
    if (raw == null) return null;
    String trimmed = raw.trim();
    try {
      return UUID.fromString(trimmed);
    } catch (IllegalArgumentException ignored) {
      String digits = trimmed.replace("-", "");
      if (digits.length() != 32) return null;
      try {
        return UUID.fromString(digits.replaceFirst(
            "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
            "$1-$2-$3-$4-$5"));
      } catch (IllegalArgumentException ignored2) {
        return null;
      }
    }
  }

  private String knownName(UUID uuid) {
    if (uuid == null) return null;
    var online = proxy.getPlayer(uuid);
    if (online.isPresent()) return online.get().getUsername();
    if (whitelist != null) {
      var entry = whitelist.lookup(uuid, null);
      if (entry.isPresent() && entry.get().lastKnownName() != null) return entry.get().lastKnownName();
    }
    if (networkBans != null) {
      var entry = networkBans.lookup(uuid, null);
      if (entry.isPresent() && entry.get().lastKnownName() != null) return entry.get().lastKnownName();
    }
    return null;
  }

  private String displayName(String name, UUID uuid) {
    if (name != null && !name.isBlank()) return name;
    if (uuid != null) return uuid.toString();
    return "unknown";
  }

  private void sendWhitelistCommand(String server, String command) {
    if (command == null || command.isBlank() || mgr == null) return;
    if (!mgr.isRunning(server)) return;
    if (!mgr.sendCommand(server, command)) {
      log.warn("Failed to dispatch '{}' to {}", command, server);
    }
  }

  private String joinArgs(String[] args, int from) {
    if (args == null || from >= args.length) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = from; i < args.length; i++) {
      if (i > from) sb.append(' ');
      sb.append(args[i]);
    }
    return sb.toString();
  }

  private static Component mmReason(String template, String player, String reason) {
    return MiniMessage.miniMessage().deserialize(normalize(template),
        Placeholder.unparsed("player", player == null ? "" : player),
        Placeholder.unparsed("reason", reason == null ? "" : reason));
  }

  private record Target(UUID uuid, String name) {}

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
        .replace("{duration}", "<duration>").replace("(duration)", "<duration>")
        .replace("{reason}", "<reason>").replace("(reason)", "<reason>");
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

  private static String renderDurationSnippet(String template, long seconds) {
    if (template == null || template.isBlank()) return "";
    String normalized = normalize(template);
    return normalized.replace("<duration>", formatDuration(seconds));
  }
}
