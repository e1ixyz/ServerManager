package com.example.soj;

import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class ServerProcessManager {
  private final Map<String, ManagedServer> servers = new HashMap<>();
  private final Config cfg;
  private final Logger log;

  public ServerProcessManager(Config cfg, Logger log) {
    this.cfg = cfg;
    this.log = log;
    for (var e : cfg.servers.entrySet()) {
      servers.put(e.getKey(), new ManagedServer(e.getKey(), e.getValue(), log));
    }
  }

  public String primary() { return cfg.primaryServerName(); }
  public boolean isKnown(String name) { return servers.containsKey(name); }

  public boolean isRunning(String name) {
    var s = servers.get(name);
    return s != null && s.isRunning();
  }

  public boolean isAnyRunning() {
    return servers.values().stream().anyMatch(ManagedServer::isRunning);
  }

  public boolean anyRecentlyStarted(long windowMs) {
    return servers.values().stream().anyMatch(s -> s.recentlyStarted(windowMs));
  }

  public synchronized void start(String name) throws IOException { get(name).start(); }
  public synchronized void stop(String name) { get(name).stopGracefully(); }
  public synchronized void stopAllGracefully() { servers.values().forEach(ManagedServer::stopGracefully); }

  private ManagedServer get(String name) {
    var s = servers.get(name);
    if (s == null) throw new IllegalArgumentException("Unknown server: " + name);
    return s;
  }
}
