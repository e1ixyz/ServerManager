package com.example.soj.commands;

import com.example.soj.Config;
import com.example.soj.ServerProcessManager;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;

public final class StatusCmd implements SimpleCommand {
  private final ServerProcessManager mgr;
  private final Config cfg;

  public StatusCmd(ServerProcessManager mgr, Config cfg) {
    this.mgr = mgr; this.cfg = cfg;
  }

  @Override
  public void execute(Invocation invocation) {
    var src = invocation.source();
    String primary = cfg.primaryServerName();
    StringBuilder sb = new StringBuilder("Servers:");
    for (var name : cfg.servers.keySet()) {
      boolean running = mgr.isRunning(name);
      boolean isPrimary = name.equals(primary);
      sb.append("\n - ").append(name)
        .append(running ? " [ONLINE]" : " [OFFLINE]")
        .append(isPrimary ? " (primary)" : "");
    }
    src.sendMessage(Component.text(sb.toString()));
  }

  @Override public boolean hasPermission(Invocation invocation) {
    var src = invocation.source();
    if (!(src instanceof Player)) return true;
    return src.hasPermission("servermanager.command.status")
        || src.hasPermission("servermanager.command.*")
        || src.hasPermission("servermanager.status")
        || src.hasPermission("servermanager.*")
        || src.hasPermission("startonjoin.status")
        || src.hasPermission("startonjoin.*");
  }
}
