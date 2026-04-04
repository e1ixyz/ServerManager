package dev.e1ixyz.servermanager.commands;

import dev.e1ixyz.servermanager.Config;
import dev.e1ixyz.servermanager.ServerManagerPlugin;
import dev.e1ixyz.servermanager.ServerProcessManager;
import dev.e1ixyz.servermanager.moderation.ModerationService;
import dev.e1ixyz.servermanager.preferences.JoinPreferenceService;
import dev.e1ixyz.servermanager.whitelist.VanillaWhitelistChecker;
import dev.e1ixyz.servermanager.whitelist.WhitelistService;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
  private static final String[] PERM_UPDATEPLUGINS = {"servermanager.command.updateplugins", "servermanager.updateplugins"};
  private static final String[] PERM_RELOAD = {"servermanager.command.reload", "servermanager.reload"};
  private static final String[] PERM_WHITELIST = {"servermanager.command.whitelist", "servermanager.whitelist"};
  private static final String[] PERM_HELP = {"servermanager.command.help", "servermanager.help"};
  private static final String[] PERM_PREFERENCE = {};
  private static final Pattern DURATION_TOKEN = Pattern.compile("(?i)(\\d+)([dhms]?)");
  private static final long HOLD_FOREVER = Long.MAX_VALUE;
  private static final int UPDATE_READY_TIMEOUT_SECONDS = 120;
  private static final int UPDATE_DRAIN_TIMEOUT_SECONDS = 20;
  private static final int UPDATE_TRANSFER_RETRY_ATTEMPTS = 5;
  private static final String WL_USAGE = "<gray>Usage:</gray> <white>/sm whitelist <green>network</green>|<green>vanilla</green> …</white>";
  private static final String WL_NETWORK_USAGE = "<gray>Usage:</gray> <white>/sm whitelist network <green>list</green>|<green>add</green>|<green>remove</green> …</white>";
  private static final String WL_VANILLA_USAGE = "<gray>Usage:</gray> <white>/sm whitelist vanilla <server> <green>list</green>|<green>add</green>|<green>remove</green>|<green>on</green>|<green>off</green>|<green>status</green> …</white>";
  private static final String PREF_USAGE = "<gray>Usage:</gray> <white>/sm preference <green>set</green> <server>|<green>clear</green>|<green>status</green>|<green>join</green> [server]</white>";
  private static final SubcommandMeta CMD_STATUS = command("status", PERM_STATUS);
  private static final SubcommandMeta CMD_START = command("start", PERM_START);
  private static final SubcommandMeta CMD_STOP = command("stop", PERM_STOP);
  private static final SubcommandMeta CMD_HOLD = command("hold", PERM_HOLD);
  private static final SubcommandMeta CMD_UPDATEPLUGINS = command("updateplugins", PERM_UPDATEPLUGINS);
  private static final SubcommandMeta CMD_RELOAD = command("reload", PERM_RELOAD);
  private static final SubcommandMeta CMD_WHITELIST = command("whitelist", PERM_WHITELIST);
  private static final SubcommandMeta CMD_PREFERENCE = command("preference", PERM_PREFERENCE, "pref", "joinpref");
  private static final SubcommandMeta CMD_HELP = command("help", PERM_HELP);
  private static final List<SubcommandMeta> SUBCOMMANDS = List.of(
      CMD_STATUS, CMD_START, CMD_STOP, CMD_HOLD, CMD_UPDATEPLUGINS, CMD_RELOAD, CMD_WHITELIST, CMD_PREFERENCE, CMD_HELP
  );
  private static final List<HelpEntry> HELP_ENTRIES = List.of(
      help(CMD_STATUS, "<white>/sm status</white> <gray>- View the state of all managed servers.</gray>"),
      help(CMD_START, "<white>/sm start [server]</white> <gray>- Start a managed server manually.</gray>"),
      help(CMD_STOP, "<white>/sm stop [server]</white> <gray>- Stop a running managed server.</gray>"),
      help(CMD_HOLD, "<white>/sm hold [server] [duration|forever|clear]</white> <gray>- Hold a server online or clear the hold.</gray>"),
      help(CMD_UPDATEPLUGINS, "<white>/sm updateplugins [server] [waiting]</white> <gray>- Move everyone to a waiting server, restart the target backend, then move everyone back.</gray>"),
      help(CMD_RELOAD, "<white>/sm reload</white> <gray>- Reload ServerManager config and listeners.</gray>"),
      help(CMD_WHITELIST, "<white>/sm whitelist network <list|add|remove></white> <gray>- Manage the shared network whitelist.</gray>"),
      help(CMD_WHITELIST, "<white>/sm whitelist vanilla [server] <list|add|remove|on|off|status></white> <gray>- Manage vanilla whitelist settings per backend.</gray>"),
      help(CMD_PREFERENCE, "<white>/sm preference <set|clear|status|join></white> <gray>- Set your preferred backend for direct joins.</gray>"),
      help(CMD_HELP, "<white>/sm help</white> <gray>- Show this staff help menu.</gray>")
  );
  private static final List<String> HOLD_KEYWORDS = List.of("clear", "cancel", "off", "remove");
  private static final List<String> HOLD_FOREVER_KEYWORDS = List.of("forever", "infinite", "inf", "infinity", "always", "permanent", "perma", "indefinite");
  private static final List<String> WHITELIST_SCOPES = List.of("network", "vanilla");
  private static final List<String> NETWORK_WL_ACTIONS = List.of("list", "add", "remove", "delete");
  private static final List<String> VANILLA_WL_ACTIONS = List.of("list", "add", "remove", "on", "off", "enable", "disable", "status", "state");
  private static final List<String> PREFERENCE_ACTIONS = List.of("set", "clear", "status", "join");
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
  private static final class PluginUpdateOperation {
    final CommandSource source;
    final String initiatedBy;
    final String updateServer;
    final String waitingServer;

    PluginUpdateOperation(CommandSource source, String initiatedBy, String updateServer, String waitingServer) {
      this.source = source;
      this.initiatedBy = initiatedBy;
      this.updateServer = updateServer;
      this.waitingServer = waitingServer;
    }
  }
  private volatile ServerProcessManager mgr;
  private volatile Config cfg;
  private volatile WhitelistService whitelist;
  private volatile VanillaWhitelistChecker vanillaWhitelist;
  private volatile ModerationService moderation;
  private volatile JoinPreferenceService joinPreferences;
  private final Logger log;
  private final ServerManagerPlugin plugin;
  private final ProxyServer proxy;
  private final Object updatePluginsLock = new Object();
  private volatile PluginUpdateOperation activeUpdatePlugins;

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
        UUID resolvedUuid = uuid;
        String resolvedName = nameHint;
        if (before.isPresent()) {
          WhitelistService.Entry entry = before.get();
          if (resolvedUuid == null) {
            resolvedUuid = entry.uuid();
          }
          if ((resolvedName == null || resolvedName.isBlank()) && entry.lastKnownName() != null && !entry.lastKnownName().isBlank()) {
            resolvedName = entry.lastKnownName();
          }
        }
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
        plugin.removeNetworkWhitelistEntry(resolvedUuid, resolvedName);
        src.sendMessage(Component.text("Removed " + displayName(resolvedName, resolvedUuid) + " from the network whitelist."));
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
        } else {
          sendWhitelistCommand(server, "whitelist reload");
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
        } else {
          sendWhitelistCommand(server, "whitelist reload");
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
                          ModerationService moderation, JoinPreferenceService joinPreferences) {
    this.mgr = mgr;
    this.cfg = cfg;
    this.whitelist = whitelist;
    this.vanillaWhitelist = vanillaWhitelist;
    this.moderation = moderation;
    this.joinPreferences = joinPreferences;
  }

  private void handlePreferenceCommand(CommandSource src, String[] args) {
    if (!(src instanceof Player player)) {
      src.sendMessage(Component.text("Only players can use preference commands."));
      return;
    }
    if (mgr == null || cfg == null || joinPreferences == null) {
      player.sendMessage(Component.text("Join preferences are not ready yet."));
      return;
    }

    if (args.length == 0 || equalsIgnoreCase(args[0], "status")) {
      showPreferenceStatus(player);
      return;
    }

    String action = args[0].toLowerCase(Locale.ROOT);
    switch (action) {
      case "set" -> {
        if (args.length < 2) {
          player.sendMessage(mm0(PREF_USAGE));
          return;
        }
        setJoinPreference(player, args[1]);
      }
      case "clear", "remove", "delete", "off", "none", "reset" -> {
        try {
          boolean removed = joinPreferences.clearPreferredServer(player.getUniqueId());
          if (removed) {
            player.sendMessage(Component.text("Cleared your preferred join server."));
          } else {
            player.sendMessage(Component.text("You do not have a preferred join server set."));
          }
        } catch (IOException ex) {
          log.error("Failed to clear join preference for {}", player.getUsername(), ex);
          player.sendMessage(Component.text("Failed to clear join preference. Please try again."));
        }
      }
      case "join" -> {
        if (args.length >= 2) {
          String explicitTarget = resolveKnownServer(args[1]);
          if (explicitTarget == null) {
            player.sendMessage(mm2(cfg.messages.unknownServer, args[1], player.getUsername()));
            return;
          }
          setJoinPreference(player, explicitTarget);
          connectPlayerToServer(player, explicitTarget);
          return;
        }
        String preferred = joinPreferences.preferredServer(player.getUniqueId());
        String resolved = resolveKnownServer(preferred);
        if (resolved == null) {
          player.sendMessage(Component.text("No valid preferred server is set. Use /sm preference set <server>."));
          return;
        }
        connectPlayerToServer(player, resolved);
      }
      default -> {
        String directTarget = resolveKnownServer(args[0]);
        if (directTarget == null) {
          player.sendMessage(mm0(PREF_USAGE));
          return;
        }
        setJoinPreference(player, directTarget);
      }
    }
  }

  private void showPreferenceStatus(Player player) {
    if (joinPreferences == null) {
      player.sendMessage(Component.text("Join preferences are not ready yet."));
      return;
    }
    String raw = joinPreferences.preferredServer(player.getUniqueId());
    if (raw == null) {
      player.sendMessage(Component.text("Preferred join server: none"));
      return;
    }
    String resolved = resolveKnownServer(raw);
    if (resolved == null) {
      player.sendMessage(Component.text("Preferred join server is set to '" + raw + "' but that server is no longer configured."));
      player.sendMessage(Component.text("Run /sm preference clear to remove it."));
      return;
    }
    player.sendMessage(Component.text("Preferred join server: " + resolved));
  }

  private void setJoinPreference(Player player, String serverToken) {
    if (joinPreferences == null) {
      player.sendMessage(Component.text("Join preferences are not ready yet."));
      return;
    }
    String resolved = resolveKnownServer(serverToken);
    if (resolved == null) {
      player.sendMessage(mm2(cfg.messages.unknownServer, serverToken, player.getUsername()));
      return;
    }

    try {
      joinPreferences.setPreferredServer(player.getUniqueId(), resolved);
      player.sendMessage(Component.text("Preferred join server set to " + resolved + "."));
      player.sendMessage(Component.text("Use /sm preference join to connect there now."));
    } catch (IOException ex) {
      log.error("Failed to save join preference for {}", player.getUsername(), ex);
      player.sendMessage(Component.text("Failed to save join preference. Please try again."));
    }
  }

  private void connectPlayerToServer(Player player, String server) {
    if (player == null || server == null) return;
    RegisteredServer target = proxy.getServer(server).orElse(null);
    if (target == null) {
      player.sendMessage(Component.text("Velocity does not know server " + server + "."));
      return;
    }

    String current = player.getCurrentServer()
        .map(conn -> conn.getServerInfo().getName())
        .orElse(null);
    if (server.equalsIgnoreCase(current)) {
      player.sendMessage(Component.text("You are already connected to " + server + "."));
      return;
    }

    player.sendMessage(Component.text("Sending you to " + server + "..."));
    player.createConnectionRequest(target).connect().whenComplete((result, error) -> {
      if (error != null) {
        log.warn("Failed preference connection for {} -> {}", player.getUsername(), server, error);
        player.sendMessage(Component.text("Failed to connect to " + server + "."));
        return;
      }
      if (result == null || result.isSuccessful()) {
        return;
      }
      result.getReasonComponent().ifPresentOrElse(
          player::sendMessage,
          () -> player.sendMessage(Component.text("Failed to connect to " + server + ".")));
    });
  }

  private String resolveKnownServer(String serverToken) {
    if (serverToken == null || mgr == null || cfg == null || cfg.servers == null || cfg.servers.isEmpty()) {
      return null;
    }
    if (mgr.isKnown(serverToken)) {
      return serverToken;
    }
    for (String configured : cfg.servers.keySet()) {
      if (configured.equalsIgnoreCase(serverToken)) {
        return configured;
      }
    }
    return null;
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
        String stopKick = firstNonBlank(config.messages.stopKick,
            "<red><white><server></white> is stopping. Please rejoin later.</red>");
        Component kickMsg = mm2(stopKick, server, nameOf(src));
        disconnectPlayersOn(server, kickMsg);
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
      case "updateplugins" -> {
        if (!has(src, PERM_UPDATEPLUGINS)) {
          src.sendMessage(mm0(config.messages.noPermission));
          return;
        }
        String updateServer = args.length >= 2 ? args[1] : null;
        String waitingServer = args.length >= 3 ? args[2] : null;
        if (updateServer == null || waitingServer == null) {
          src.sendMessage(mmUpdate(firstNonBlank(config.messages.updatePluginsUsage, config.messages.usage),
              updateServer, waitingServer, nameOf(src), ""));
          return;
        }
        if (!manager.isKnown(updateServer)) {
          src.sendMessage(mm2(config.messages.unknownServer, updateServer, nameOf(src)));
          return;
        }
        if (!manager.isKnown(waitingServer)) {
          src.sendMessage(mm2(config.messages.unknownServer, waitingServer, nameOf(src)));
          return;
        }
        if (updateServer.equalsIgnoreCase(waitingServer)) {
          src.sendMessage(mmUpdate(firstNonBlank(config.messages.updatePluginsSameServer,
                  "<red><white><server></white> and <white><waiting></white> must be different servers.</red>"),
              updateServer, waitingServer, nameOf(src), ""));
          return;
        }
        startPluginUpdate(src, updateServer, waitingServer);
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
      case "preference", "pref", "joinpref" -> handlePreferenceCommand(src, tail(args, 1));
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
      case "updateplugins" -> suggestUpdatePlugins(args);
      case "whitelist" -> suggestWhitelist(args);
      case "preference" -> suggestPreference(args);
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

  private List<String> suggestUpdatePlugins(String[] args) {
    if (args.length == 2) {
      return suggestServers(args[1]);
    }
    if (args.length == 3) {
      Config config = this.cfg;
      if (config == null || config.servers == null || config.servers.isEmpty()) {
        return List.of();
      }
      List<String> options = new ArrayList<>();
      for (String name : config.servers.keySet()) {
        if (!name.equalsIgnoreCase(args[1])) {
          options.add(name);
        }
      }
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

  private List<String> suggestPreference(String[] args) {
    if (args.length == 2) {
      List<String> options = new ArrayList<>(PREFERENCE_ACTIONS);
      options.addAll(cfg.servers.keySet());
      return filterOptions(options, args[1]);
    }
    if (args.length == 3) {
      if (equalsIgnoreCase(args[1], "set", "join")) {
        return suggestServers(args[2]);
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
    if (command == null || command.isBlank()) return;
    plugin.sendBackendCommandWhenReady(server, command);
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
        .replace("{waiting}", "<waiting>").replace("(waiting)", "<waiting>")
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

  private static Component mmUpdate(String template, String server, String waiting, String player, String reason) {
    return MINI.deserialize(normalize(template),
        Placeholder.unparsed("server", server == null ? "" : server),
        Placeholder.unparsed("waiting", waiting == null ? "" : waiting),
        Placeholder.unparsed("player", player == null ? "" : player),
        Placeholder.unparsed("reason", reason == null ? "" : reason));
  }

  private void disconnectPlayersOn(String server, Component message) {
    if (server == null || message == null) return;
    proxy.getAllPlayers().forEach(p -> p.getCurrentServer().ifPresent(cs -> {
      if (cs.getServerInfo().getName().equals(server)) {
        p.disconnect(message);
      }
    }));
  }

  private void startPluginUpdate(CommandSource src, String updateServer, String waitingServer) {
    PluginUpdateOperation op = new PluginUpdateOperation(src, nameOf(src), updateServer, waitingServer);
    synchronized (updatePluginsLock) {
      if (activeUpdatePlugins != null) {
        Config config = this.cfg;
        String template = (config != null && config.messages != null)
            ? firstNonBlank(config.messages.updatePluginsBusy,
            "<yellow>Another plugin update workflow is already running.</yellow>")
            : "<yellow>Another plugin update workflow is already running.</yellow>";
        src.sendMessage(mmUpdate(template, updateServer, waitingServer, op.initiatedBy, ""));
        return;
      }
      activeUpdatePlugins = op;
    }

    Config config = this.cfg;
    String preparing = (config != null && config.messages != null)
        ? firstNonBlank(config.messages.updatePluginsPreparing,
        "<yellow>Starting <white><waiting></white> before restarting <white><server></white>...</yellow>")
        : "<yellow>Starting <white><waiting></white> before restarting <white><server></white>...</yellow>";
    src.sendMessage(mmUpdate(preparing, updateServer, waitingServer, op.initiatedBy, ""));
    ensureServerReady(op, waitingServer, this::onWaitingServerReady);
  }

  private void onWaitingServerReady(PluginUpdateOperation op) {
    if (!isActiveUpdate(op)) return;
    Config config = this.cfg;
    String moving = (config != null && config.messages != null)
        ? firstNonBlank(config.messages.updatePluginsMoving,
        "<yellow><white><waiting></white> is ready. Moving all players before restarting <white><server></white>...</yellow>")
        : "<yellow><white><waiting></white> is ready. Moving all players before restarting <white><server></white>...</yellow>";
    op.source.sendMessage(mmUpdate(moving, op.updateServer, op.waitingServer, op.initiatedBy, ""));
    moveAllPlayersToServer(op, op.waitingServer, config != null && config.messages != null
        ? config.messages.updatePluginsPlayerWaiting
        : "<yellow>Plugin updates are in progress. Sending you to <white><waiting></white>...</yellow>");
    waitForServerToDrain(op, op.updateServer, this::restartUpdateServer);
  }

  private void restartUpdateServer(PluginUpdateOperation op) {
    if (!isActiveUpdate(op)) return;
    Config config = this.cfg;
    String restarting = (config != null && config.messages != null)
        ? firstNonBlank(config.messages.updatePluginsRestarting,
        "<yellow>Restarting <white><server></white> now...</yellow>")
        : "<yellow>Restarting <white><server></white> now...</yellow>";
    op.source.sendMessage(mmUpdate(restarting, op.updateServer, op.waitingServer, op.initiatedBy, ""));

    proxy.getScheduler().buildTask(plugin, () -> {
      if (!isActiveUpdate(op)) return;
      ServerProcessManager manager = this.mgr;
      if (manager == null || !manager.isKnown(op.updateServer)) {
        failPluginUpdate(op, "update server is no longer configured", null);
        return;
      }
      try {
        if (manager.isRunning(op.updateServer)) {
          manager.stop(op.updateServer);
        }
      } catch (Exception ex) {
        failPluginUpdate(op, "failed to stop " + op.updateServer, ex);
        return;
      }
      try {
        manager.start(op.updateServer);
      } catch (IOException ex) {
        failPluginUpdate(op, "failed to start " + op.updateServer, ex);
        return;
      }
      ensureServerReady(op, op.updateServer, this::finishPluginUpdate);
    }).schedule();
  }

  private void finishPluginUpdate(PluginUpdateOperation op) {
    if (!isActiveUpdate(op)) return;
    Config config = this.cfg;
    String returning = (config != null && config.messages != null)
        ? firstNonBlank(config.messages.updatePluginsReturning,
        "<green><white><server></white> is back online. Sending players there now...</green>")
        : "<green><white><server></white> is back online. Sending players there now...</green>";
    op.source.sendMessage(mmUpdate(returning, op.updateServer, op.waitingServer, op.initiatedBy, ""));
    moveAllPlayersToServer(op, op.updateServer, config != null && config.messages != null
        ? config.messages.updatePluginsPlayerReturning
        : "<green>Plugin updates finished. Sending you to <white><server></white>...</green>");
    String complete = (config != null && config.messages != null)
        ? firstNonBlank(config.messages.updatePluginsComplete,
        "<green>Plugin update flow complete. Players were returned to <white><server></white>.</green>")
        : "<green>Plugin update flow complete. Players were returned to <white><server></white>.</green>";
    op.source.sendMessage(mmUpdate(complete, op.updateServer, op.waitingServer, op.initiatedBy, ""));
    clearActiveUpdate(op);
  }

  private void ensureServerReady(PluginUpdateOperation op, String serverName,
                                 java.util.function.Consumer<PluginUpdateOperation> onReady) {
    if (!isActiveUpdate(op)) return;
    ServerProcessManager manager = this.mgr;
    if (manager == null || !manager.isKnown(serverName)) {
      failPluginUpdate(op, serverName + " is no longer configured", null);
      return;
    }
    try {
      if (!manager.isRunning(serverName)) {
        manager.start(serverName);
      }
    } catch (IOException ex) {
      failPluginUpdate(op, "failed to start " + serverName, ex);
      return;
    }

    Optional<RegisteredServer> target = proxy.getServer(serverName);
    if (target.isEmpty()) {
      failPluginUpdate(op, "Velocity does not know server " + serverName, null);
      return;
    }

    AtomicBoolean resolved = new AtomicBoolean(false);
    final ScheduledTask[] poller = new ScheduledTask[1];
    long deadline = System.currentTimeMillis() + UPDATE_READY_TIMEOUT_SECONDS * 1000L;
    poller[0] = proxy.getScheduler().buildTask(plugin, () -> {
      if (!isActiveUpdate(op)) {
        ScheduledTask task = poller[0];
        if (task != null) task.cancel();
        return;
      }
      ServerProcessManager currentManager = this.mgr;
      if (currentManager == null || !currentManager.isKnown(serverName)) {
        if (resolved.compareAndSet(false, true)) {
          ScheduledTask task = poller[0];
          if (task != null) task.cancel();
          failPluginUpdate(op, serverName + " is no longer configured", null);
        }
        return;
      }
      if (System.currentTimeMillis() >= deadline) {
        if (resolved.compareAndSet(false, true)) {
          ScheduledTask task = poller[0];
          if (task != null) task.cancel();
          failPluginUpdate(op, serverName + " did not come up in time", null);
        }
        return;
      }
      if (!currentManager.isRunning(serverName)) {
        return;
      }
      target.get().ping().whenComplete((ping, err) -> {
        if (err != null || !resolved.compareAndSet(false, true)) return;
        ScheduledTask task = poller[0];
        if (task != null) task.cancel();
        proxy.getScheduler().buildTask(plugin, () -> onReady.accept(op)).schedule();
      });
    }).delay(Duration.ZERO).repeat(Duration.ofSeconds(1)).schedule();
  }

  private void moveAllPlayersToServer(PluginUpdateOperation op, String targetServer, String playerTemplate) {
    if (!isActiveUpdate(op)) return;
    Optional<RegisteredServer> target = proxy.getServer(targetServer);
    if (target.isEmpty()) {
      failPluginUpdate(op, "Velocity does not know server " + targetServer, null);
      return;
    }
    Component notice = null;
    if (playerTemplate != null && !playerTemplate.isBlank()) {
      notice = mmUpdate(playerTemplate, op.updateServer, op.waitingServer, op.initiatedBy, "");
    }
    for (Player player : proxy.getAllPlayers()) {
      if (notice != null) {
        player.sendMessage(notice);
      }
      String current = player.getCurrentServer()
          .map(cs -> cs.getServerInfo().getName())
          .orElse(null);
      if (targetServer.equalsIgnoreCase(current)) {
        continue;
      }
      attemptUpdatePlayerTransfer(op, player.getUniqueId(), target.get(), targetServer, 0);
    }
  }

  private void attemptUpdatePlayerTransfer(PluginUpdateOperation op, UUID playerUuid,
                                           RegisteredServer targetServer, String targetName, int attempt) {
    if (!isActiveUpdate(op)) return;
    Player player = proxy.getPlayer(playerUuid).orElse(null);
    if (player == null) {
      return;
    }

    player.createConnectionRequest(targetServer).connect().whenComplete((result, error) -> {
      if (!isActiveUpdate(op) || proxy.getPlayer(playerUuid).isEmpty()) {
        return;
      }
      if (error != null) {
        if (attempt < UPDATE_TRANSFER_RETRY_ATTEMPTS) {
          retryUpdatePlayerTransfer(op, playerUuid, targetName, attempt + 1);
          return;
        }
        log.warn("Failed to move {} to {} during plugin update flow", player.getUsername(), targetName, error);
        return;
      }
      if (result == null || result.isSuccessful()) {
        return;
      }
      if (result.getStatus() == com.velocitypowered.api.proxy.ConnectionRequestBuilder.Status.SERVER_DISCONNECTED
          && attempt < UPDATE_TRANSFER_RETRY_ATTEMPTS) {
        retryUpdatePlayerTransfer(op, playerUuid, targetName, attempt + 1);
      }
    });
  }

  private void retryUpdatePlayerTransfer(PluginUpdateOperation op, UUID playerUuid, String targetName, int nextAttempt) {
    proxy.getScheduler().buildTask(plugin, () -> {
      if (!isActiveUpdate(op)) return;
      RegisteredServer target = proxy.getServer(targetName).orElse(null);
      if (target == null) {
        return;
      }
      attemptUpdatePlayerTransfer(op, playerUuid, target, targetName, nextAttempt);
    }).delay(Duration.ofSeconds(2)).schedule();
  }

  private void waitForServerToDrain(PluginUpdateOperation op, String serverName,
                                    java.util.function.Consumer<PluginUpdateOperation> onDrain) {
    if (!isActiveUpdate(op)) return;
    if (countPlayersOnServer(serverName) == 0) {
      onDrain.accept(op);
      return;
    }

    AtomicBoolean resolved = new AtomicBoolean(false);
    final ScheduledTask[] poller = new ScheduledTask[1];
    long deadline = System.currentTimeMillis() + UPDATE_DRAIN_TIMEOUT_SECONDS * 1000L;
    poller[0] = proxy.getScheduler().buildTask(plugin, () -> {
      if (!isActiveUpdate(op)) {
        ScheduledTask task = poller[0];
        if (task != null) task.cancel();
        return;
      }
      int playersRemaining = countPlayersOnServer(serverName);
      if (playersRemaining <= 0) {
        if (resolved.compareAndSet(false, true)) {
          ScheduledTask task = poller[0];
          if (task != null) task.cancel();
          onDrain.accept(op);
        }
        return;
      }
      if (System.currentTimeMillis() >= deadline && resolved.compareAndSet(false, true)) {
        ScheduledTask task = poller[0];
        if (task != null) task.cancel();
        log.warn("[{}] {} player(s) still remained on the backend after waiting {}s; continuing plugin update flow.",
            serverName, playersRemaining, UPDATE_DRAIN_TIMEOUT_SECONDS);
        onDrain.accept(op);
      }
    }).delay(Duration.ofSeconds(1)).repeat(Duration.ofSeconds(1)).schedule();
  }

  private int countPlayersOnServer(String serverName) {
    int count = 0;
    for (Player player : proxy.getAllPlayers()) {
      boolean onTarget = player.getCurrentServer()
          .map(cs -> cs.getServerInfo().getName().equals(serverName))
          .orElse(false);
      if (onTarget) {
        count++;
      }
    }
    return count;
  }

  private boolean isActiveUpdate(PluginUpdateOperation op) {
    return activeUpdatePlugins == op;
  }

  private void clearActiveUpdate(PluginUpdateOperation op) {
    synchronized (updatePluginsLock) {
      if (activeUpdatePlugins == op) {
        activeUpdatePlugins = null;
      }
    }
  }

  private void failPluginUpdate(PluginUpdateOperation op, String reason, Throwable error) {
    if (error != null) {
      log.error("Plugin update workflow failed for {} -> {}", op.updateServer, op.waitingServer, error);
    } else {
      log.warn("Plugin update workflow failed for {} -> {}: {}", op.updateServer, op.waitingServer, reason);
    }
    if (!isActiveUpdate(op)) return;
    Config config = this.cfg;
    String template = (config != null && config.messages != null)
        ? firstNonBlank(config.messages.updatePluginsFailed, "<red>Plugin update flow failed: <reason></red>")
        : "<red>Plugin update flow failed: <reason></red>";
    op.source.sendMessage(mmUpdate(template, op.updateServer, op.waitingServer, op.initiatedBy, firstNonBlank(reason, "unknown error")));
    clearActiveUpdate(op);
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
