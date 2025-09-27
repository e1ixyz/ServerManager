package com.example.soj.commands;

import com.example.soj.ServerProcessManager;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;

public final class StopCmd implements SimpleCommand {
  private final ServerProcessManager mgr;

  public StopCmd(ServerProcessManager mgr) {
    this.mgr = mgr;
  }

  @Override
  public void execute(Invocation invocation) {
    var src = invocation.source();
    var args = invocation.arguments();
    if (args.length != 1) {
      src.sendMessage(Component.text("Usage: /svstop <server>"));
      return;
    }
    String name = args[0];
    try {
      mgr.stop(name);
      src.sendMessage(Component.text("Stopping " + name + "..."));
    } catch (Exception ex) {
      src.sendMessage(Component.text("Failed to stop " + name + ": " + ex.getMessage()));
    }
  }

  @Override public boolean hasPermission(Invocation invocation) {
    var src = invocation.source();
    if (!(src instanceof Player)) return true;
    return src.hasPermission("servermanager.command.stop")
        || src.hasPermission("servermanager.command.*")
        || src.hasPermission("servermanager.stop")
        || src.hasPermission("servermanager.*")
        || src.hasPermission("startonjoin.stop")
        || src.hasPermission("startonjoin.*");
  }
}
