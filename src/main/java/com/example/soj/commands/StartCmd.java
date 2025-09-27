package com.example.soj.commands;

import com.example.soj.Config;
import com.example.soj.ServerProcessManager;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

public final class StartCmd implements SimpleCommand {
  private final ServerProcessManager mgr;
  private final Config cfg;
  private final Logger log;

  public StartCmd(ServerProcessManager mgr, Config cfg, Logger log) {
    this.mgr = mgr; this.cfg = cfg; this.log = log;
  }

  @Override
  public void execute(Invocation invocation) {
    var src = invocation.source();
    var args = invocation.arguments();
    if (args.length != 1) {
      src.sendMessage(Component.text("Usage: /svstart <server>"));
      return;
    }
    String name = args[0];
    if (!mgr.isKnown(name)) {
      src.sendMessage(Component.text("Unknown server: " + name));
      return;
    }
    try {
      mgr.start(name);
      src.sendMessage(Component.text("Starting " + name + "..."));
    } catch (Exception ex) {
      log.error("Error starting {}", name, ex);
      src.sendMessage(Component.text("Failed to start " + name + ": " + ex.getMessage()));
    }
  }

  @Override public boolean hasPermission(Invocation invocation) {
    var src = invocation.source();
    if (!(src instanceof Player)) return true;
    return src.hasPermission("servermanager.command.start")
        || src.hasPermission("servermanager.command.*")
        || src.hasPermission("servermanager.start")
        || src.hasPermission("servermanager.*")
        || src.hasPermission("startonjoin.start")
        || src.hasPermission("startonjoin.*");
  }
}
