package com.example.soj;

import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class ServerProcessManager {
  private final Map<String, ManagedServer> servers = new HashMap<>();
  private final Map<String, Long> holdUntilMs = new HashMap<>();
  private volatile Config cfg;
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

  public synchronized long hold(String name, long durationSeconds) {
    if (durationSeconds <= 0) {
      holdUntilMs.remove(name);
      return 0L;
    }
    get(name); // validate server exists
    if (durationSeconds == Long.MAX_VALUE) {
      holdUntilMs.put(name, Long.MAX_VALUE);
      return Long.MAX_VALUE;
    }
    long now = System.currentTimeMillis();
    long durationMs;
    if (durationSeconds > Long.MAX_VALUE / 1000L) {
      durationMs = Long.MAX_VALUE - now;
    } else {
      durationMs = durationSeconds * 1000L;
      if (durationMs > Long.MAX_VALUE - now) {
        durationMs = Long.MAX_VALUE - now;
      }
    }
    long expiry = now + durationMs;
    holdUntilMs.put(name, expiry);
    return expiry;
  }

  public synchronized boolean clearHold(String name) {
    return holdUntilMs.remove(name) != null;
  }

  public synchronized boolean isHoldActive(String name) {
    Long until = holdUntilMs.get(name);
    if (until == null) return false;
    if (until == Long.MAX_VALUE) return true;
    long now = System.currentTimeMillis();
    if (until <= now) {
      holdUntilMs.remove(name);
      return false;
    }
    return true;
  }

  public synchronized long holdRemainingSeconds(String name) {
    Long until = holdUntilMs.get(name);
    if (until == null) return 0L;
    if (until == Long.MAX_VALUE) return Long.MAX_VALUE;
    long now = System.currentTimeMillis();
    if (until <= now) {
      holdUntilMs.remove(name);
      return 0L;
    }
    long diff = until - now;
    return (diff + 999L) / 1000L; // ceiling division to seconds
  }

  public synchronized void start(String name) throws IOException { get(name).start(); }
  public synchronized void stop(String name) { get(name).stopGracefully(); }
  public synchronized void stopAllGracefully() { servers.values().forEach(ManagedServer::stopGracefully); }
  public synchronized boolean sendCommand(String name, String command) {
    return get(name).sendCommand(command);
  }

  public synchronized void reload(Config newCfg) {
    Map<String, ManagedServer> updated = new HashMap<>();
    for (var entry : newCfg.servers.entrySet()) {
      String name = entry.getKey();
      ServerConfig cfg = entry.getValue();
      ManagedServer existing = servers.get(name);
      if (existing != null) {
        existing.updateConfig(cfg);
        updated.put(name, existing);
      } else {
        updated.put(name, new ManagedServer(name, cfg, log));
      }
    }

    for (var entry : servers.entrySet()) {
      if (!updated.containsKey(entry.getKey())) {
        try {
          entry.getValue().stopGracefully();
        } catch (Exception ex) {
          log.warn("Failed to stop removed server {} during reload", entry.getKey(), ex);
        }
      }
    }

    servers.clear();
    servers.putAll(updated);
    holdUntilMs.keySet().retainAll(updated.keySet());
    this.cfg = newCfg;
  }

  private ManagedServer get(String name) {
    var s = servers.get(name);
    if (s == null) throw new IllegalArgumentException("Unknown server: " + name);
    return s;
  }
}
