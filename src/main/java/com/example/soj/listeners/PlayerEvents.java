package com.example.soj.listeners;

import com.example.soj.Config;
import com.example.soj.ServerProcessManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Features:
 * - Dynamic MOTD via MiniMessage; shows "online" only after a successful ping.
 * - Primary start-on-first-join (kick-to-start).
 * - /server <target>: starts offline target, keeps player online, auto-sends when ready.
 * - Per-server stop: when a backend becomes empty, stop after stopGraceSeconds.
 * - Proxy-empty stop: if proxy has zero players, stop ALL after stopGraceSeconds
 *   (with startupGraceSeconds bump if a server just started).
 */
public final class PlayerEvents {
  /** How long to wait for a started backend before timing out auto-send (seconds). */
  private static final int START_CONNECT_TIMEOUT_SECONDS = 90;

  private final Object pluginOwner;
  private final ProxyServer proxy;
  private final Config cfg;
  private final ServerProcessManager mgr;
  private final Logger log;

  /** Proxy-empty stop-all task */
  private volatile ScheduledTask pendingStopAll;

  /** Per-server scheduled stop tasks for empty servers */
  private final Map<String, ScheduledTask> pendingServerStop = new ConcurrentHashMap<>();

  /** Per-player poller state for “wait until backend is ready, then connect”. */
  private final Map<UUID, ScheduledTask> pendingConnect = new ConcurrentHashMap<>();
  private final Map<UUID, Long> pendingConnectStartMs = new ConcurrentHashMap<>();
  /** Which target a player is currently waiting for (de-dupes requeues). */
  private final Map<UUID, String> waitingTarget = new ConcurrentHashMap<>();
  /** Ensures the "ready → sending you now" path fires only once per wait. */
  private final Set<UUID> readyNotifyOnce = ConcurrentHashMap.newKeySet();

  /** Lightweight readiness cache: true only after a successful ping. */
  private final Map<String, Boolean> isReadyCache = new ConcurrentHashMap<>();
  @SuppressWarnings("FieldCanBeLocal")
  private final ScheduledTask heartbeatTask;

  public PlayerEvents(Object pluginOwner, ProxyServer proxy, Config cfg, ServerProcessManager mgr, Logger log) {
    this.pluginOwner = pluginOwner;
    this.proxy = proxy;
    this.cfg = cfg;
    this.mgr = mgr;
    this.log = log;

    // Periodically ping all known servers to update readiness (for accurate MOTD)
    this.heartbeatTask = proxy.getScheduler().buildTask(pluginOwner, () -> {
      for (String name : cfg.servers.keySet()) {
        if (!mgr.isRunning(name)) {
          isReadyCache.put(name, Boolean.FALSE);
          continue;
        }
        proxy.getServer(name).ifPresent(rs -> rs.ping().whenComplete((ok, err) -> {
          isReadyCache.put(name, err == null);
        }));
      }
    }).delay(Duration.ofSeconds(1)).repeat(Duration.ofSeconds(2)).schedule();
  }

  // -------------------- MOTD --------------------
  @Subscribe
  public void onPing(ProxyPingEvent e) {
    String primary = cfg.primaryServerName();

    boolean online = false;
    if (primary != null) {
      // Show online only when a ping recently succeeded
      online = Boolean.TRUE.equals(isReadyCache.get(primary));
      if (isReadyCache.get(primary) == null && !mgr.isRunning(primary)) {
        online = false;
      }
    }

    String l1 = online ? cfg.motd.online  : cfg.motd.offline;
    String l2 = online ? cfg.motd.online2 : cfg.motd.offline2;
    String mm = (l2 == null || l2.isBlank()) ? (l1 == null ? "" : l1) : (l1 + "<newline>" + l2);

    Component description = MiniMessage.miniMessage().deserialize(mm);
    e.setPing(e.getPing().asBuilder().description(description).build());
  }

  // -------------- Primary start-on-first-join --------------
  @Subscribe
  public void onLogin(LoginEvent e) {
    cancelPendingStopAll(); // activity on proxy
    cancelPendingConnect(e.getPlayer().getUniqueId()); // clear any previous wait

    String primary = cfg.primaryServerName();
    if (primary == null) return;

    Player joining = e.getPlayer();
    if (proxy.getAllPlayers().isEmpty() && !mgr.isRunning(primary)) {
      try {
        log.info("First join detected by {} -> starting primary [{}]", joining.getUsername(), primary);
        mgr.start(primary);
      } catch (IOException ex) {
        log.error("Failed to start primary server {}", primary, ex);
      }
      // Kick to menu with simple text (disconnect screen isn't MiniMessage)
      e.setResult(LoginEvent.ComponentResult.denied(Component.text(cfg.kickMessage)));
      // After kick, proxy becomes empty; arm stop-all safety with grace bump
      scheduleStopAllIfProxyEmpty();
    }
  }

  // -------------- Gate all connects (/server <target>, menu join, etc.) --------------
  @Subscribe
  public void onServerPreConnect(ServerPreConnectEvent event) {
    RegisteredServer original = event.getOriginalServer();
    String target = (original != null) ? original.getServerInfo().getName() : null;
    if (target == null) return;
    if (!mgr.isKnown(target)) return; // only manage servers present in our config

    String primary = cfg.primaryServerName();
    boolean running = mgr.isRunning(target);
    int onlineCount = proxy.getAllPlayers().size();

    // Primary, first-ever join from menu -> legacy (start + disconnect)
    if (target.equals(primary) && !running && onlineCount <= 1) {
      try {
        log.info("Intercepting first join to [{}]; starting & kicking with '{}'", primary, cfg.kickMessage);
        mgr.start(primary);
      } catch (IOException ex) {
        log.error("Failed to start primary server {}", primary, ex);
      }
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      event.getPlayer().disconnect(Component.text(cfg.kickMessage));
      // Proxy will be empty after disconnect; ensure stop-all timer is armed
      scheduleStopAllIfProxyEmpty();
      return;
    }

    // Any managed server (including primary when not first-join):
    // If offline, start it, deny immediate connect, message the player, and queue auto-send.
    if (!running) {
      UUID id = event.getPlayer().getUniqueId();

      String waiting = waitingTarget.get(id);
      if (target.equals(waiting)) { // already queued for this server
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        return;
      }
      if (waiting != null && !waiting.equals(target)) { // switching queue target
        cancelPendingConnect(id);
      }

      try {
        log.info("Intercepting connect to offline [{}]; starting and queueing auto-connect", target);
        mgr.start(target);
      } catch (IOException ex) {
        log.error("Failed to start server {}", target, ex);
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        event.getPlayer().sendMessage(mm(cfg.messages.startFailed, target, event.getPlayer().getUsername()));
        return;
      }

      // Deny immediate connect; keep player online where they are
      event.setResult(ServerPreConnectEvent.ServerResult.denied());

      Player p = event.getPlayer();
      p.sendMessage(mm(cfg.messages.startingQueued, target, p.getUsername()));
      queueAutoSend(p, target);
    }
  }

  // -------------- Per-server stop scheduling on switch/leave --------------
  @Subscribe
  public void onServerConnected(ServerConnectedEvent e) {
    // Player reached a backend
    cancelPendingStopAll(); // proxy not empty

    // Cancel any pending stop for the server they just joined
    String joined = e.getServer().getServerInfo().getName();
    cancelPendingServerStop(joined);

    // If they switched from another backend, maybe that one is now empty -> schedule stop for it
    e.getPreviousServer().ifPresent(prev -> {
      String prevName = prev.getServerInfo().getName();
      scheduleStopIfServerEmpty(prevName);
    });
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent e) {
    UUID id = e.getPlayer().getUniqueId();
    cancelPendingConnect(id); // if waiting, stop the poller

    // If they were on a backend, maybe that backend is now empty
    e.getPlayer().getCurrentServer().ifPresent(conn -> {
      String name = conn.getServerInfo().getName();
      scheduleStopIfServerEmpty(name);
    });

    // If the proxy became empty, also arm the stop-all as a safety net
    scheduleStopAllIfProxyEmpty();
  }

  // -------------------- Stop helpers --------------------
  /** Schedule a stop for this backend if it currently has zero players. */
  private void scheduleStopIfServerEmpty(String serverName) {
    if (!mgr.isKnown(serverName)) return;
    if (countPlayersOn(serverName) > 0) return;
    if (pendingServerStop.containsKey(serverName)) return;

    int delay = Math.max(0, cfg.stopGraceSeconds);
    log.info("[{}] no players online; scheduling stop in {}s", serverName, delay);

    ScheduledTask task = proxy.getScheduler().buildTask(pluginOwner, () -> {
      pendingServerStop.remove(serverName);
      if (countPlayersOn(serverName) > 0) {
        log.info("[{}] stop skipped; players rejoined.", serverName);
        return;
      }
      try {
        log.info("[{}] stopping (empty for {}s)...", serverName, delay);
        mgr.stop(serverName);
        isReadyCache.put(serverName, Boolean.FALSE);
      } catch (Exception ex) {
        log.error("Failed to stop server {}", serverName, ex);
      }
    }).delay(Duration.ofSeconds(delay)).schedule();

    pendingServerStop.put(serverName, task);
  }

  /** Cancel a pending per-server stop. */
  private void cancelPendingServerStop(String serverName) {
    ScheduledTask t = pendingServerStop.remove(serverName);
    if (t != null) {
      t.cancel();
      log.info("[{}] canceling scheduled stop; player activity detected.", serverName);
    }
  }

  /** Arm a stop-all when the entire proxy is empty (covers kick-to-start case). */
  private void scheduleStopAllIfProxyEmpty() {
    // Debounce slightly to let Velocity update player list
    proxy.getScheduler().buildTask(pluginOwner, () -> {
      if (!proxy.getAllPlayers().isEmpty()) return;
      if (pendingStopAll != null) return;

      long graceMs = Math.max(0, cfg.startupGraceSeconds) * 1000L;
      boolean startupGraceActive = mgr.anyRecentlyStarted(graceMs);
      int delaySeconds = Math.max(0, cfg.stopGraceSeconds)
          + (startupGraceActive ? Math.max(0, cfg.startupGraceSeconds) : 0);

      pendingStopAll = proxy.getScheduler().buildTask(pluginOwner, () -> {
        pendingStopAll = null;
        if (!proxy.getAllPlayers().isEmpty()) return;

        if (startupGraceActive && mgr.anyRecentlyStarted(graceMs)) {
          log.info("Proxy empty but within startup grace; skipping stop-all this time.");
          return;
        }

        log.info("Proxy empty for {}s; stopping all managed servers...", delaySeconds);
        mgr.stopAllGracefully();
        cfg.servers.keySet().forEach(n -> isReadyCache.put(n, Boolean.FALSE));
      }).delay(Duration.ofSeconds(delaySeconds)).schedule();

      if (startupGraceActive) {
        log.info("Proxy empty; scheduled stop-all in {}s ({}s stop + {}s grace).",
            delaySeconds, cfg.stopGraceSeconds, cfg.startupGraceSeconds);
      } else {
        log.info("Proxy empty; scheduled stop-all in {}s.", cfg.stopGraceSeconds);
      }
    }).delay(Duration.ofSeconds(2)).schedule();
  }

  private void cancelPendingStopAll() {
    ScheduledTask t = pendingStopAll;
    if (t != null) {
      t.cancel();
      pendingStopAll = null;
      log.info("Canceled pending stop-all due to player activity.");
    }
  }

  // -------------------- Auto-send poller --------------------
  private void queueAutoSend(Player player, String serverName) {
    UUID id = player.getUniqueId();
    cancelPendingConnect(id);
    pendingConnectStartMs.put(id, System.currentTimeMillis());
    waitingTarget.put(id, serverName);
    // Allow one “ready” fire for this new wait
    readyNotifyOnce.remove(id);

    var task = proxy.getScheduler().buildTask(pluginOwner, () -> {
      var current = proxy.getPlayer(id);
      if (current.isEmpty()) {
        cancelPendingConnect(id);
        return;
      }

      String nowWaiting = waitingTarget.get(id);
      if (nowWaiting == null || !nowWaiting.equals(serverName)) {
        cancelPendingConnect(id);
        return;
      }

      long start = pendingConnectStartMs.getOrDefault(id, System.currentTimeMillis());
      if ((System.currentTimeMillis() - start) > START_CONNECT_TIMEOUT_SECONDS * 1000L) {
        cancelPendingConnect(id);
        player.sendMessage(mm(cfg.messages.timeout, serverName, player.getUsername()));
        // Enforce "no backend without players" if nobody reached it
        if (countPlayersOn(serverName) == 0 && mgr.isRunning(serverName)) {
          try {
            log.info("[{}] auto-send timeout; no players joined -> stopping.", serverName);
            mgr.stop(serverName);
            isReadyCache.put(serverName, Boolean.FALSE);
          } catch (Exception ex) {
            log.error("Failed to stop server {} after timeout", serverName, ex);
          }
        }
        return;
      }

      var opt = proxy.getServer(serverName);
      if (opt.isEmpty()) {
        cancelPendingConnect(id);
        player.sendMessage(mm(cfg.messages.unknownServer, serverName, player.getUsername()));
        return;
      }

      var rs = opt.get();
      rs.ping().whenComplete((ping, err) -> {
        if (err != null) return; // still not ready
        isReadyCache.put(serverName, Boolean.TRUE);

        if (!serverName.equals(waitingTarget.get(id))) return;
        if (!readyNotifyOnce.add(id)) return; // already fired once for this wait

        // Ensure player is still online on the proxy before messaging/connecting
        if (proxy.getPlayer(id).isEmpty()) {
          cancelPendingConnect(id);
          return;
        }

        // Stop the poller but DO NOT clear readyNotifyOnce here
        cancelPendingConnect(id);

        proxy.getScheduler().buildTask(pluginOwner, () -> {
          player.sendMessage(mm(cfg.messages.readySending, serverName, player.getUsername()));
          cancelPendingServerStop(serverName); // ensure no pending empty-stop fights us
          player.createConnectionRequest(rs).connect();
        }).schedule();
      });
    }).delay(Duration.ofSeconds(1)).repeat(Duration.ofSeconds(1)).schedule();

    pendingConnect.put(id, task);
  }

  private void cancelPendingConnect(UUID id) {
    ScheduledTask t = pendingConnect.remove(id);
    if (t != null) t.cancel();
    pendingConnectStartMs.remove(id);
    waitingTarget.remove(id);
    // IMPORTANT: do NOT clear readyNotifyOnce here.
    // It is cleared only when starting a new wait in queueAutoSend().
  }

  // -------------------- Utility --------------------
  private int countPlayersOn(String serverName) {
    int[] c = new int[1];
    proxy.getAllPlayers().forEach(p -> p.getCurrentServer().ifPresent(cs -> {
      if (cs.getServerInfo().getName().equals(serverName)) c[0]++;
    }));
    return c[0];
  }

  private static String normalizePlaceholders(String s) {
    if (s == null) return "";
    return s
        .replace("{server}", "<server>").replace("(server)", "<server>")
        .replace("{player}", "<player>").replace("(player)", "<player>")
        .replace("{state}", "<state>").replace("(state)", "<state>");
  }

  private static Component mm(String template, String server, String player) {
    String t = normalizePlaceholders(template);
    return MiniMessage.miniMessage().deserialize(t,
        Placeholder.unparsed("server", server == null ? "" : server),
        Placeholder.unparsed("player", player == null ? "" : player));
  }
}