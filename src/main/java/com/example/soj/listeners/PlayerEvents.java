package com.example.soj.listeners;

import com.example.soj.Config;
import com.example.soj.ServerProcessManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
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
 * Handles: dynamic MOTD (MiniMessage), primary start-on-first-join (kick-to-start),
 * non-primary /server starts with in-proxy queue+auto-connect, and stop-on-empty with grace.
 * Also: readiness cache (only show online when ping succeeds) and single-fire "ready" messages.
 */
public final class PlayerEvents {
  /** How long to wait for a started backend before timing out auto-send (seconds). */
  private static final int START_CONNECT_TIMEOUT_SECONDS = 90;

  private final Object pluginOwner;
  private final ProxyServer proxy;
  private final Config cfg;
  private final ServerProcessManager mgr;
  private final Logger log;

  /** Scheduled “stop all” when proxy is empty (debounced & graceful). */
  private volatile ScheduledTask pendingStop;

  /** Per-player poller state for “wait until backend is ready, then connect”. */
  private final Map<UUID, ScheduledTask> pendingConnect = new ConcurrentHashMap<>();
  private final Map<UUID, Long> pendingConnectStartMs = new ConcurrentHashMap<>();
  /** Which target a player is currently waiting for (de-dupes requeues). */
  private final Map<UUID, String> waitingTarget = new ConcurrentHashMap<>();
  /** Ensures the "ready → sending you now" path fires only once per wait. */
  private final Set<UUID> readyNotifyOnce = ConcurrentHashMap.newKeySet();

  /** Lightweight readiness cache: true only after a successful ping. */
  private final Map<String, Boolean> isReadyCache = new ConcurrentHashMap<>();
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
        // If not running, it's definitely not ready
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

  // ------------------------------------------------------------
  // MOTD (MiniMessage; 2 lines from config; "online" only after ping success)
  // ------------------------------------------------------------
  @Subscribe
  public void onPing(ProxyPingEvent e) {
    String primary = cfg.primaryServerName();

    boolean online = false;
    if (primary != null) {
      // Show online only when we KNOW it's ready (successful ping in the last heartbeat)
      online = Boolean.TRUE.equals(isReadyCache.get(primary));
      // If we have no cache yet and it's not running, definitely offline
      if (isReadyCache.get(primary) == null && !mgr.isRunning(primary)) {
        online = false;
      }
    }

    String l1 = online ? cfg.motd.online  : cfg.motd.offline;
    String l2 = online ? cfg.motd.online2 : cfg.motd.offline2;
    String mm = (l2 == null || l2.isBlank())
        ? (l1 == null ? "" : l1)
        : (l1 + "<newline>" + l2);

    Component description = MiniMessage.miniMessage().deserialize(mm);
    e.setPing(e.getPing().asBuilder().description(description).build());
  }

  // ------------------------------------------------------------
  // Primary “first player joins from menu” path:
  // If proxy empty & primary offline: start it and disconnect with kickMessage.
  // ------------------------------------------------------------
  @Subscribe
  public void onLogin(LoginEvent e) {
    cancelPendingStop(); // activity: don’t stop now
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
      // Disconnect to menu with a simple text (disconnect screen is not MiniMessage)
      e.setResult(LoginEvent.ComponentResult.denied(Component.text(cfg.kickMessage)));
    }
  }

  // ------------------------------------------------------------
  // Gate for all connect attempts (covers /server <target>):
  // Primary first-join still kicks; all other offline targets start + queue + auto-send.
  // ------------------------------------------------------------
  @Subscribe
  public void onServerPreConnect(ServerPreConnectEvent event) {
    RegisteredServer original = event.getOriginalServer();
    String target = (original != null) ? original.getServerInfo().getName() : null;
    if (target == null) return;
    if (!mgr.isKnown(target)) return; // only manage servers present in our config

    String primary = cfg.primaryServerName();
    boolean running = mgr.isRunning(target);
    int onlineCount = proxy.getAllPlayers().size();

    // Primary, first-ever join from menu -> keep legacy behavior (start + disconnect)
    if (target.equals(primary) && !running && onlineCount <= 1) {
      try {
        log.info("Intercepting first join to [{}]; starting & kicking with '{}'", primary, cfg.kickMessage);
        mgr.start(primary);
      } catch (IOException ex) {
        log.error("Failed to start primary server {}", primary, ex);
      }
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      event.getPlayer().disconnect(Component.text(cfg.kickMessage));
      return;
    }

    // Any managed server (including primary when not first-join):
    // If offline, start it, deny immediate connect, message the player, and queue auto-send.
    if (!running) {
      UUID id = event.getPlayer().getUniqueId();

      // If we’re already waiting for this exact server, just deny again (no spam / no requeue).
      String waiting = waitingTarget.get(id);
      if (target.equals(waiting)) {
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        return;
      }

      // If we were waiting for a different server, cancel that wait and switch.
      if (waiting != null && !waiting.equals(target)) {
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

      // Keep the player online where they are; we’ll send them when ready.
      event.setResult(ServerPreConnectEvent.ServerResult.denied());

      Player p = event.getPlayer();
      p.sendMessage(mm(cfg.messages.startingQueued, target, p.getUsername()));
      queueAutoSend(p, target);
    }
  }

  // ------------------------------------------------------------
  // Stop-on-empty with grace (includes startupGrace bump if a backend just started)
  // ------------------------------------------------------------
  @Subscribe
  public void onDisconnect(DisconnectEvent e) {
    cancelPendingConnect(e.getPlayer().getUniqueId()); // if waiting, stop the poller
    scheduleStopIfEmpty();
  }

  private void scheduleStopIfEmpty() {
    // Debounce a moment so Velocity’s player list is accurate
    proxy.getScheduler().buildTask(pluginOwner, () -> {
      if (!proxy.getAllPlayers().isEmpty()) return;

      if (pendingStop != null) return; // already scheduled

      long graceMs = Math.max(0, cfg.startupGraceSeconds) * 1000L;
      boolean startupGraceActive = mgr.anyRecentlyStarted(graceMs);

      int delaySeconds = Math.max(0, cfg.stopGraceSeconds)
          + (startupGraceActive ? Math.max(0, cfg.startupGraceSeconds) : 0);

      pendingStop = proxy.getScheduler().buildTask(pluginOwner, () -> {
        pendingStop = null;
        if (!proxy.getAllPlayers().isEmpty()) return;

        // Safety: if still within startup grace, skip once.
        if (startupGraceActive && mgr.anyRecentlyStarted(graceMs)) {
          log.info("Proxy empty but within startup grace; skipping this stop.");
          return;
        }

        log.info("Proxy has been empty for {}s; stopping all managed servers...", delaySeconds);
        mgr.stopAllGracefully();

        // Once we stop everything, clear readiness cache
        for (String name : cfg.servers.keySet()) {
          isReadyCache.put(name, Boolean.FALSE);
        }
      }).delay(Duration.ofSeconds(delaySeconds)).schedule();

      if (startupGraceActive) {
        log.info("Proxy empty, startup grace active; scheduled stop-all in {}s ({}s stop + {}s grace).",
            delaySeconds, cfg.stopGraceSeconds, cfg.startupGraceSeconds);
      } else {
        log.info("Scheduled stop-all in {}s (proxy currently empty).", cfg.stopGraceSeconds);
      }
    }).delay(Duration.ofSeconds(2)).schedule();
  }

  private void cancelPendingStop() {
    ScheduledTask t = pendingStop;
    if (t != null) {
      t.cancel();
      pendingStop = null;
      log.info("Canceled pending stop-all due to new player activity.");
    }
  }

  // ------------------------------------------------------------
  // Auto-send logic: poll the target server until it’s ready, then connect the player
  // ------------------------------------------------------------
  private void queueAutoSend(Player player, String serverName) {
    UUID id = player.getUniqueId();
    cancelPendingConnect(id); // de-dupe per player
    pendingConnectStartMs.put(id, System.currentTimeMillis());
    waitingTarget.put(id, serverName);
    readyNotifyOnce.remove(id); // allow one "ready" fire for this new wait

    var task = proxy.getScheduler().buildTask(pluginOwner, () -> {
      // Still online on the proxy?
      var current = proxy.getPlayer(id);
      if (current.isEmpty()) {
        cancelPendingConnect(id);
        return;
      }

      // If they switched to a different target meanwhile, stop this poller.
      String nowWaiting = waitingTarget.get(id);
      if (nowWaiting == null || !nowWaiting.equals(serverName)) {
        cancelPendingConnect(id);
        return;
      }

      // Timeout?
      long start = pendingConnectStartMs.getOrDefault(id, System.currentTimeMillis());
      if ((System.currentTimeMillis() - start) > START_CONNECT_TIMEOUT_SECONDS * 1000L) {
        cancelPendingConnect(id);
        player.sendMessage(mm(cfg.messages.timeout, serverName, player.getUsername()));
        return;
      }

      // Is this server registered in Velocity?
      var opt = proxy.getServer(serverName);
      if (opt.isEmpty()) {
        cancelPendingConnect(id);
        player.sendMessage(mm(cfg.messages.unknownServer, serverName, player.getUsername()));
        return;
      }

      var rs = opt.get();

      // Ping to see if it’s ready. (No executor param; we’ll hop to scheduler after.)
      rs.ping().whenComplete((ping, err) -> {
        if (err != null) return; // still not ready; try again next tick

        // Update readiness cache
        isReadyCache.put(serverName, Boolean.TRUE);

        // Double-check we’re still waiting for THIS server & only fire once.
        if (!serverName.equals(waitingTarget.get(id))) return;
        if (!readyNotifyOnce.add(id)) return; // already fired once for this wait

        // It’s ready! Cancel polling and hop onto Velocity’s scheduler to connect.
        cancelPendingConnect(id);
        proxy.getScheduler().buildTask(pluginOwner, () -> {
          player.sendMessage(mm(cfg.messages.readySending, serverName, player.getUsername()));
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
    readyNotifyOnce.remove(id);
  }

  // ------------------------------------------------------------
  // MiniMessage helpers with placeholders {server}/{player} OR <server>/<player>
  // ------------------------------------------------------------
  private static String normalizePlaceholders(String s) {
    if (s == null) return "";
    // Accept {server} / (server) / <server> (same for player/state)
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
