package com.example.soj.commands;

import com.example.soj.Config;
import com.example.soj.ServerProcessManager;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.io.IOException;

public final class ServerManagerCmd implements SimpleCommand {
  private final ServerProcessManager mgr;
  private final Config cfg;
  private final Logger log;

  public ServerManagerCmd(ServerProcessManager mgr, Config cfg, Logger log) {
    this.mgr = mgr;
    this.cfg = cfg;
    this.log = log;
  }

  @Override
  public void execute(Invocation inv) {
    var src = inv.source();
    var args = inv.arguments();

    if (args.length == 0) {
      src.sendMessage(mm0(cfg.messages.usage));
      return;
    }

    String sub = args[0].toLowerCase();
    String server = (args.length >= 2 ? args[1] : null);

    switch (sub) {
      case "status" -> {
        if (!has(src, "servermanager.status")) { src.sendMessage(mm0(cfg.messages.noPermission)); return; }
        src.sendMessage(mm0(cfg.messages.statusHeader));
        for (var name : cfg.servers.keySet()) {
          boolean running = mgr.isRunning(name);
          String state = running ? cfg.messages.stateOnline : cfg.messages.stateOffline;
          src.sendMessage(mmState(cfg.messages.statusLine, name, state));
        }
      }
      case "start" -> {
        if (!has(src, "servermanager.start")) { src.sendMessage(mm0(cfg.messages.noPermission)); return; }
        if (server == null) { src.sendMessage(mm0(cfg.messages.usage)); return; }
        if (!mgr.isKnown(server)) { src.sendMessage(mm2(cfg.messages.unknownServer, server, nameOf(src))); return; }
        if (mgr.isRunning(server)) { src.sendMessage(mm2(cfg.messages.alreadyRunning, server, nameOf(src))); return; }
        try {
          mgr.start(server);
          src.sendMessage(mm2(cfg.messages.started, server, nameOf(src)));
        } catch (IOException ex) {
          log.error("Failed to start {}", server, ex);
          src.sendMessage(mm2(cfg.messages.startFailed, server, nameOf(src)));
        }
      }
      case "stop" -> {
        if (!has(src, "servermanager.stop")) { src.sendMessage(mm0(cfg.messages.noPermission)); return; }
        if (server == null) { src.sendMessage(mm0(cfg.messages.usage)); return; }
        if (!mgr.isKnown(server)) { src.sendMessage(mm2(cfg.messages.unknownServer, server, nameOf(src))); return; }
        if (!mgr.isRunning(server)) { src.sendMessage(mm2(cfg.messages.alreadyStopped, server, nameOf(src))); return; }
        mgr.stop(server);
        src.sendMessage(mm2(cfg.messages.stopped, server, nameOf(src)));
      }
      default -> src.sendMessage(mm0(cfg.messages.usage));
    }
  }

  private static boolean has(com.velocitypowered.api.command.CommandSource src, String perm) {
    return src.hasPermission(perm);
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
