package com.example.soj.commands;

import com.example.soj.ServerProcessManager;
import com.velocitypowered.api.command.SimpleCommand;
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
    return invocation.source().hasPermission("startonjoin.stop");
  }
}
