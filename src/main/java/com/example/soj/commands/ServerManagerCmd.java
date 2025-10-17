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

public final class ServerManagerCmd implements SimpleCommand {
  private static final String[] WILDCARD_PERMS = {
      "servermanager.command.*",
      "servermanager.*",
      "startonjoin.*"
  };
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

    String sub = args[0].toLowerCase();
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

  // ----- MiniMessage helpers (normalize {server}/(server)/<server> etc.) -----
  private static String normalize(String s) {
    if (s == null) return "";
    return s
        .replace("{server}", "<server>").replace("(server)", "<server>")
        .replace("{player}", "<player>").replace("(player)", "<player>")
        .replace("{state}", "<state>").replace("(state)", "<state>");
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
}
