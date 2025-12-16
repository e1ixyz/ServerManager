package com.example.soj.commands;

import com.example.soj.Config;
import com.example.soj.ServerManagerPlugin;
import com.example.soj.ServerProcessManager;
import com.example.soj.moderation.ModerationService;
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
import java.util.Collection;
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
  private static final MiniMessage MINI = MiniMessage.miniMessage();
  private static final String[] PERM_STATUS = {"servermanager.command.status", "servermanager.status"};
  private static final String[] PERM_START = {"servermanager.command.start", "servermanager.start"};
  private static final String[] PERM_STOP = {"servermanager.command.stop", "servermanager.stop"};
  private static final String[] PERM_HOLD = {"servermanager.command.hold", "servermanager.hold"};
  private static final String[] PERM_RELOAD = {"servermanager.command.reload", "servermanager.reload"};
  private static final String[] PERM_WHITELIST = {"servermanager.command.whitelist", "servermanager.whitelist"};
  private static final String[] PERM_HELP = {"servermanager.command.help", "servermanager.help"};
  private static final Pattern DURATION_TOKEN = Pattern.compile("(?i)(\\d+)([dhms]?)");
  private static final long HOLD_FOREVER = Long.MAX_VALUE;
  private static final String WL_USAGE = "<gray>Usage:</gray> <white>/sm whitelist <green>network</green>|<green>vanilla</green> …</white>";
  private static final String WL_NETWORK_USAGE = "<gray>Usage:</gray> <white>/sm whitelist network <green>list</green>|<green>add</green>|<green>remove</green> …</white>";
  private static final String WL_VANILLA_USAGE = "<gray>Usage:</gray> <white>/sm whitelist vanilla <server> <green>list</green>|<green>add</green>|<green>remove</green>|<green>on</green>|<green>off</green>|<green>status</green> …</white>";
  private static final SubcommandMeta CMD_STATUS = command("status", PERM_STATUS);
  private static final SubcommandMeta CMD_START = command("start", PERM_START);
  private static final SubcommandMeta CMD_STOP = command("stop", PERM_STOP);
  private static final SubcommandMeta CMD_HOLD = command("hold", PERM_HOLD);
  private static final SubcommandMeta CMD_RELOAD = command("reload", PERM_RELOAD);
  private static final SubcommandMeta CMD_WHITELIST = command("whitelist", PERM_WHITELIST);
  private static final SubcommandMeta CMD_HELP = command("help", PERM_HELP);
  private static final List<SubcommandMeta> SUBCOMMANDS = List.of(
      CMD_STATUS, CMD_START, CMD_STOP, CMD_HOLD, CMD_RELOAD, CMD_WHITELIST, CMD_HELP
  );
  private static final List<HelpEntry> HELP_ENTRIES = List.of(
      help(CMD_STATUS, "<white>/sm status</white> <gray>- View the state of all managed servers.</gray>"),
      help(CMD_START, "<white>/sm start [server]</white> <gray>- Start a managed server manually.</gray>"),
      help(CMD_STOP, "<white>/sm stop [server]</white> <gray>- Stop a running managed server.</gray>"),
      help(CMD_HOLD, "<white>/sm hold [server] [duration|forever|clear]</white> <gray>- Hold a server online or clear the hold.</gray>"),
      help(CMD_RELOAD, "<white>/sm reload</white> <gray>- Reload ServerManager config and listeners.</gray>"),
      help(CMD_WHITELIST, "<white>/sm whitelist network <list|add|remove></white> <gray>- Manage the shared network whitelist.</gray>"),
      help(CMD_WHITELIST, "<white>/sm whitelist vanilla [server] <list|add|remove|on|off|status></white> <gray>- Manage vanilla whitelist settings per backend.</gray>"),
      help(CMD_HELP, "<white>/sm help</white> <gray>- Show this staff help menu.</gray>")
  );
  private static final List<String> HOLD_KEYWORDS = List.of("clear", "cancel", "off", "remove");
  private static final List<String> HOLD_FOREVER_KEYWORDS = List.of("forever", "infinite", "inf", "infinity", "always", "permanent", "perma", "indefinite");
  private static final List<String> WHITELIST_SCOPES = List.of("network", "vanilla");
  private static final List<String> NETWORK_WL_ACTIONS = List.of("list", "add", "remove", "delete");
  private static final List<String> VANILLA_WL_ACTIONS = List.of("list", "add", "remove", "on", "off", "enable", "disable", "status", "state");
  private static SubcommandMeta command(String primary, String[] perms, String... aliases) {
    List<String> triggers = new ArrayList<>();
    triggers.add(primary);
    if (aliases != null && aliases.length > 0) {
      triggers.addAll(Arrays.asList(aliases));
    }
    return new SubcommandMeta(primary, List.copyOf(triggers), perms);
  }

  private static HelpEntry help(SubcommandMeta command, String template) {
    return new HelpEntry(command, MINI.deserialize(template));
  }

  private record SubcommandMeta(String primary, List<String> triggers, String[] perms) {
    boolean matches(String token) {
      if (token == null) return false;
      for (String trigger : triggers) {
        if (trigger.equalsIgnoreCase(token)) return true;
      }
      return false;
    }

    boolean canUse(CommandSource src) {
      if (perms == null || perms.length == 0) return true;
      return ServerManagerCmd.has(src, perms);
    }
  }

  private record HelpEntry(SubcommandMeta command, Component line) {
    boolean visibleTo(CommandSource src) {
      return command == null || command.canUse(src);
    }
  }
  private volatile ServerProcessManager mgr;
  private volatile Config cfg;
  private volatile WhitelistService whitelist;
  private volatile VanillaWhitelistChecker vanillaWhitelist;
  private volatile ModerationService moderation;
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
      case "on", "enable", "enabled" -> {
        try {
          boolean changed = vanillaWhitelist.setWhitelistEnabled(server, true);
          sendWhitelistCommand(server, "whitelist on");
          String suffix = (mgr != null && mgr.isRunning(server)) ? "." : " (applies on next start if offline).";
          String prefix = changed ? "Vanilla whitelist for " + server + " is now enabled" : "Vanilla whitelist for " + server + " was already enabled";
          src.sendMessage(Component.text(prefix + suffix));
        } catch (IOException ex) {
          log.error("Failed to enable vanilla whitelist", ex);
          src.sendMessage(Component.text("Failed to update whitelist setting: " + ex.getMessage()));
        }
      }
      case "off", "disable", "disabled" -> {
        try {
          boolean changed = vanillaWhitelist.setWhitelistEnabled(server, false);
          sendWhitelistCommand(server, "whitelist off");
          String suffix = (mgr != null && mgr.isRunning(server)) ? "." : " (applies on next start if offline).";
          String prefix = changed ? "Vanilla whitelist for " + server + " is now disabled" : "Vanilla whitelist for " + server + " was already disabled";
          src.sendMessage(Component.text(prefix + suffix));
        } catch (IOException ex) {
          log.error("Failed to disable vanilla whitelist", ex);
          src.sendMessage(Component.text("Failed to update whitelist setting: " + ex.getMessage()));
        }
      }
      case "status", "state" -> {
        boolean enabled = vanillaWhitelist.isWhitelistEnabled(server);
        src.sendMessage(Component.text("Vanilla whitelist for " + server + " is currently " + (enabled ? "enabled." : "disabled.")));
      }
      default -> src.sendMessage(mm0(WL_VANILLA_USAGE));
    }
  }

  public void updateState(ServerProcessManager mgr, Config cfg,
                          WhitelistService whitelist, VanillaWhitelistChecker vanillaWhitelist,
                          ModerationService moderation) {
    this.mgr = mgr;
    this.cfg = cfg;
    this.whitelist = whitelist;
    this.vanillaWhitelist = vanillaWhitelist;
    this.moderation = moderation;
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
        if (!has(src, PERM_STATUS)) { src.sendMessage(mm0(config.messages.noPermission)); return; }
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
        if (!has(src, PERM_START)) { src.sendMessage(mm0(config.messages.noPermission)); return; }
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
        if (!has(src, PERM_STOP)) { src.sendMessage(mm0(config.messages.noPermission)); return; }
        if (server == null) { src.sendMessage(mm0(config.messages.usage)); return; }
        if (!manager.isKnown(server)) { src.sendMessage(mm2(config.messages.unknownServer, server, nameOf(src))); return; }
        if (!manager.isRunning(server)) { src.sendMessage(mm2(config.messages.alreadyStopped, server, nameOf(src))); return; }
        manager.stop(server);
        src.sendMessage(mm2(config.messages.stopped, server, nameOf(src)));
      }
      case "help" -> {
        if (!has(src, PERM_HELP)) {
          src.sendMessage(mm0(config.messages.noPermission));
          return;
        }
        if (config.messages.helpHeader != null && !config.messages.helpHeader.isBlank()) {
          src.sendMessage(mm0(config.messages.helpHeader));
        }
        boolean any = false;
        for (HelpEntry entry : HELP_ENTRIES) {
          if (entry.visibleTo(src)) {
            src.sendMessage(entry.line());
            any = true;
          }
        }
        if (!any) {
          src.sendMessage(mm0("<gray>No commands available for your permissions.</gray>"));
        }
      }
      case "hold" -> {
        if (!has(src, PERM_HOLD)) {
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
        if (!has(src, PERM_RELOAD)) { src.sendMessage(mm0(config.messages.noPermission)); return; }
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
        if (!has(src, PERM_WHITELIST)) {
          src.sendMessage(mm0(config.messages.noPermission));
          return;
        }
        handleWhitelistCommand(src, tail(args, 1));
      }
      default -> src.sendMessage(mm0(config.messages.usage));
    }
  }

  @Override
  public List<String> suggest(Invocation invocation) {
    var src = invocation.source();
    var args = invocation.arguments();
    if (mgr == null || cfg == null) return List.of();

    if (args.length == 0) {
      return suggestSubcommands(src, "");
    }
    if (args.length == 1) {
      return suggestSubcommands(src, args[0]);
    }

    SubcommandMeta meta = findSubcommand(args[0]);
    if (meta == null || !meta.canUse(src)) {
      return List.of();
    }
    String canonical = meta.primary();

    return switch (canonical) {
      case "start" -> suggestServers(args[1]);
      case "stop" -> suggestServers(args[1]);
      case "hold" -> suggestHold(args);
      case "whitelist" -> suggestWhitelist(args);
      case "status", "reload", "help" -> List.of();
      default -> List.of();
    };
  }

  private List<String> suggestSubcommands(CommandSource src, String partial) {
    String lower = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
    List<String> matches = new ArrayList<>();
    for (SubcommandMeta meta : SUBCOMMANDS) {
      if (!meta.canUse(src)) continue;
      for (String trigger : meta.triggers()) {
        if (lower.isEmpty() || trigger.toLowerCase(Locale.ROOT).startsWith(lower)) {
          matches.add(trigger);
        }
      }
    }
    matches.sort(String.CASE_INSENSITIVE_ORDER);
    return matches;
  }

  private static SubcommandMeta findSubcommand(String token) {
    if (token == null) return null;
    for (SubcommandMeta meta : SUBCOMMANDS) {
      if (meta.matches(token)) return meta;
    }
    return null;
  }

  private List<String> suggestServers(String partial) {
    Config config = this.cfg;
    if (config == null || config.servers == null || config.servers.isEmpty()) {
      return List.of();
    }
    return filterOptions(config.servers.keySet(), partial);
  }

  private List<String> suggestVanillaServers(String partial) {
    VanillaWhitelistChecker checker = this.vanillaWhitelist;
    if (checker == null || !checker.hasTargets()) {
      return List.of();
    }
    return filterOptions(checker.trackedServers(), partial);
  }

  private List<String> suggestHold(String[] args) {
    if (args.length <= 2) {
      return suggestServers(args.length >= 2 ? args[1] : "");
    }
    if (args.length == 3) {
      List<String> options = new ArrayList<>(HOLD_KEYWORDS);
      options.addAll(HOLD_FOREVER_KEYWORDS);
      return filterOptions(options, args[2]);
    }
    return List.of();
  }

  private List<String> suggestWhitelist(String[] args) {
    if (args.length == 2) {
      return filterOptions(WHITELIST_SCOPES, args[1]);
    }
    if (args.length >= 3) {
      String scope = args[1];
      if ("network".equalsIgnoreCase(scope)) {
        return suggestWhitelistNetwork(args);
      }
      if ("vanilla".equalsIgnoreCase(scope)) {
        return suggestWhitelistVanilla(args);
      }
    }
    return List.of();
  }

  private List<String> suggestWhitelistNetwork(String[] args) {
    if (args.length == 3) {
      return filterOptions(NETWORK_WL_ACTIONS, args[2]);
    }
    if (args.length == 4) {
      String action = args[2];
      if (equalsIgnoreCase(action, "add", "remove", "delete")) {
        return suggestOnlinePlayers(args[3]);
      }
    }
    return List.of();
  }

  private List<String> suggestWhitelistVanilla(String[] args) {
    if (args.length == 3) {
      return suggestVanillaServers(args[2]);
    }
    if (args.length == 4) {
      return filterOptions(VANILLA_WL_ACTIONS, args[3]);
    }
    if (args.length == 5) {
      String action = args[3];
      if (equalsIgnoreCase(action, "add", "remove")) {
        return suggestOnlinePlayers(args[4]);
      }
    }
    return List.of();
  }

  private List<String> suggestOnlinePlayers(String partial) {
    Collection<Player> players = proxy.getAllPlayers();
    if (players == null || players.isEmpty()) {
      return List.of();
    }
    List<String> matches = new ArrayList<>();
    String lower = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
    for (Player player : players) {
      if (player == null) continue;
      String name = player.getUsername();
      if (name == null) continue;
      if (lower.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(lower)) {
        matches.add(name);
      }
    }
    matches.sort(String.CASE_INSENSITIVE_ORDER);
    return matches;
  }

  private List<String> filterOptions(Collection<? extends String> options, String partial) {
    if (options == null || options.isEmpty()) {
      return List.of();
    }
    String lower = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
    List<String> matches = new ArrayList<>();
    for (String option : options) {
      if (option == null) continue;
      if (lower.isEmpty() || option.toLowerCase(Locale.ROOT).startsWith(lower)) {
        matches.add(option);
      }
    }
    matches.sort(String.CASE_INSENSITIVE_ORDER);
    return matches;
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
    return MINI.deserialize(normalize(template),
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
    return MINI.deserialize(normalize(template));
  }

  private static Component mm2(String template, String server, String player) {
    return MINI.deserialize(normalize(template),
        Placeholder.unparsed("server", server == null ? "" : server),
        Placeholder.unparsed("player", player == null ? "" : player));
  }

  private static Component mmState(String template, String server, String state) {
    return MINI.deserialize(normalize(template),
        Placeholder.unparsed("server", server == null ? "" : server),
        // 'state' may itself contain MiniMessage markup (e.g., <green>online</green>)
        Placeholder.parsed("state", state == null ? "" : state));
  }

  private static Component mmDuration(String template, String server, String player, String duration) {
    return MINI.deserialize(normalize(template),
        Placeholder.unparsed("server", server == null ? "" : server),
        Placeholder.unparsed("player", player == null ? "" : player),
        Placeholder.unparsed("duration", duration == null ? "" : duration));
  }

  private static long parseDurationSeconds(String raw) {
    if (raw == null) return -1;
    String trimmed = raw.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    if (trimmed.isEmpty()) return -1;
    if (isHoldForever(trimmed)) return HOLD_FOREVER;

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
    if (seconds == HOLD_FOREVER) return "forever";
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

  private static boolean isHoldForever(String token) {
    if (token == null) return false;
    for (String kw : HOLD_FOREVER_KEYWORDS) {
      if (kw.equalsIgnoreCase(token)) return true;
    }
    return false;
  }
}
