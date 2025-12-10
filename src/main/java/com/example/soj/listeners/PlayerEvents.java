package com.example.soj.listeners;

import com.example.soj.Config;
import com.example.soj.ServerProcessManager;
import com.example.soj.bans.NetworkBanService;
import com.example.soj.whitelist.VanillaWhitelistChecker;
import com.example.soj.whitelist.WhitelistService;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
  private final WhitelistService whitelist;
  private final VanillaWhitelistChecker vanillaWhitelist;
  private final NetworkBanService networkBans;
  private final Config.Whitelist whitelistCfg;
  private final Map<String, Config.ForcedHost> forcedHostOverrides;
  private final Set<String> vanillaBypassServers;
  private final Set<String> mirrorNetworkWhitelistServers;

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
  /** Last backend each player successfully reached (for disconnect scheduling). */
  private final Map<UUID, String> lastKnownServer = new ConcurrentHashMap<>();

  /** Lightweight readiness cache: true only after a successful ping. */
  private final Map<String, Boolean> isReadyCache = new ConcurrentHashMap<>();
  /** Auto-restart schedules for indefinite holds. */
  private final Map<String, RestartSchedule> holdRestartTasks = new ConcurrentHashMap<>();
  @SuppressWarnings("FieldCanBeLocal")
  private final ScheduledTask heartbeatTask;

  private static final class RestartSchedule {
    final long restartAtMs;
    ScheduledTask warn1m;
    ScheduledTask warn5s;
    ScheduledTask restart;

    RestartSchedule(long restartAtMs) {
      this.restartAtMs = restartAtMs;
    }
  }

  public PlayerEvents(
      Object pluginOwner,
      ProxyServer proxy,
      Config cfg,
      ServerProcessManager mgr,
      Logger log,
      WhitelistService whitelist,
      VanillaWhitelistChecker vanillaWhitelist,
      NetworkBanService networkBans
  ) {
    this.pluginOwner = pluginOwner;
    this.proxy = proxy;
    this.cfg = cfg;
    this.mgr = mgr;
    this.log = log;
    this.whitelist = whitelist;
    this.vanillaWhitelist = vanillaWhitelist;
    this.networkBans = networkBans;
    this.whitelistCfg = (whitelist != null) ? whitelist.config() : null;
    this.forcedHostOverrides = new HashMap<>();
    if (cfg.forcedHosts != null) {
      cfg.forcedHosts.forEach((host, settings) -> {
        String normalized = normalizeHost(host);
        if (normalized != null && settings != null) {
          forcedHostOverrides.put(normalized, settings);
          String target = sanitizeServerName(settings.server);
          log.info("Registered forced-host override {} -> {}", normalized, target == null ? "<unset>" : target);
        }
      });
    }

    this.vanillaBypassServers = new HashSet<>();
    this.mirrorNetworkWhitelistServers = new HashSet<>();
    boolean globalVanillaBypass = cfg.whitelist != null && cfg.whitelist.allowVanillaBypass;
    String primary = cfg.primaryServerName();
    cfg.servers.forEach((name, serverCfg) -> {
      if (serverCfg == null) return;
      boolean bypass = (serverCfg.vanillaWhitelistBypassesNetwork != null)
          ? Boolean.TRUE.equals(serverCfg.vanillaWhitelistBypassesNetwork)
          : (globalVanillaBypass && name.equals(primary));
      if (bypass) {
        vanillaBypassServers.add(name);
      }

      boolean mirror = (serverCfg.mirrorNetworkWhitelist != null)
          ? Boolean.TRUE.equals(serverCfg.mirrorNetworkWhitelist)
          : name.equals(primary);
      if (mirror) {
        mirrorNetworkWhitelistServers.add(name);
      }
    });
    if (!vanillaBypassServers.isEmpty()) {
      log.info("Vanilla whitelist bypass enabled for: {}", vanillaBypassServers);
    }
    if (!mirrorNetworkWhitelistServers.isEmpty()) {
      log.info("Network whitelist entries will mirror to: {}", mirrorNetworkWhitelistServers);
    }

    Map<String, List<String>> velocityHosts = proxy.getConfiguration().getForcedHosts();
    if (velocityHosts != null && !velocityHosts.isEmpty()) {
      velocityHosts.forEach((host, targets) -> {
        log.info("Velocity forced-host {} -> {}", host, targets);
      });
    } else {
      log.info("Velocity forced-host configuration empty.");
    }

    // Periodically ping all known servers to update readiness (for accurate MOTD)
    this.heartbeatTask = proxy.getScheduler().buildTask(pluginOwner, () -> {
      for (String name : cfg.servers.keySet()) {
        refreshHoldRestart(name);
        if (!mgr.isRunning(name)) {
          isReadyCache.put(name, Boolean.FALSE);
          continue;
        }
        if (countPlayersOn(name) == 0 && !mgr.isHoldActive(name)) {
          scheduleStopIfServerEmpty(name);
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
    String rawHost = e.getConnection().getVirtualHost()
        .map(addr -> addr.getHostString())
        .orElse(null);
    String normalizedHost = normalizeHost(rawHost);
    Config.ForcedHost hostCfg = (normalizedHost == null) ? null : forcedHostOverrides.get(normalizedHost);

    Config.Motd baseMotd = cfg.motd;
    Config.Motd override = (hostCfg != null) ? hostCfg.motd : null;

    String offline = override != null && override.offline != null ? override.offline : baseMotd.offline;
    String offline2 = override != null && override.offline2 != null ? override.offline2 : baseMotd.offline2;
    String starting = override != null && override.starting != null ? override.starting : baseMotd.starting;
    String starting2 = override != null && override.starting2 != null ? override.starting2 : baseMotd.starting2;
    String online = override != null && override.online != null ? override.online : baseMotd.online;
    String online2 = override != null && override.online2 != null ? override.online2 : baseMotd.online2;

    String trackedServer = resolveTrackedServer(hostCfg, normalizedHost);

    String l1 = offline;
    String l2 = offline2;

    if (trackedServer != null) {
      boolean running = mgr.isRunning(trackedServer);
      Boolean ready = isReadyCache.get(trackedServer);
      boolean onlineState = Boolean.TRUE.equals(ready);
      boolean startingState = running && !onlineState;

      if (onlineState) {
        l1 = online;
        l2 = online2;
      } else if (startingState) {
        l1 = firstNonBlank(starting, online, offline);
        l2 = firstNonBlank(starting2, online2, offline2);
      } else {
        l1 = offline;
        l2 = offline2;
      }
    }

    String mm = (l2 == null || l2.isBlank()) ? (l1 == null ? "" : l1) : (l1 + "<newline>" + l2);
    String normalized = normalizePlaceholders(mm);
    Component description = MiniMessage.miniMessage().deserialize(normalized,
        Placeholder.unparsed("server", trackedServer == null ? "" : trackedServer));
    e.setPing(e.getPing().asBuilder().description(description).build());
  }

  // -------------- Primary start-on-first-join --------------
  @Subscribe
  public void onLogin(LoginEvent e) {
    cancelPendingStopAll(); // activity on proxy
    Player joining = e.getPlayer();
    cancelPendingConnect(joining.getUniqueId()); // clear any previous wait

    if (isNetworkBanned(joining, true)) {
      e.setResult(LoginEvent.ComponentResult.denied(Component.empty()));
      return;
    }

    if (!hasNetworkAccess(joining)) {
      handleNotWhitelisted(joining, true);
      e.setResult(LoginEvent.ComponentResult.denied(Component.empty()));
    }
  }

  // -------------- Gate all connects (/server <target>, menu join, etc.) --------------
  @Subscribe
  public void onServerPreConnect(ServerPreConnectEvent event) {
    RegisteredServer original = event.getOriginalServer();
    RegisteredServer effective = event.getResult().getServer().orElse(original);
    String target = (effective != null) ? effective.getServerInfo().getName() : null;
    if (target == null) return;
    if (!mgr.isKnown(target)) return; // only manage servers present in our config

    Player player = event.getPlayer();
    if (isNetworkBanned(player, true)) {
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      return;
    }
    String primary = cfg.primaryServerName();
    boolean running = mgr.isRunning(target);
    boolean isPrimary = target.equals(primary);
    boolean hasFallback = player.getCurrentServer().isPresent();

    Config.ForcedHost hostCfg = player.getVirtualHost()
        .map(addr -> forcedHostOverrides.get(normalizeHost(addr.getHostString()))).orElse(null);
    String kickMessage = resolveKickMessage(hostCfg);

    if (isPrimary) {
      if (!hasNetworkAccess(player)) {
        handleNotWhitelisted(player, true);
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        return;
      }
    } else {
      if (vanillaWhitelist != null
          && vanillaWhitelist.tracksServer(target)
          && vanillaWhitelist.isWhitelistEnabled(target)
          && !vanillaWhitelist.isWhitelisted(target, player.getUniqueId(), player.getUsername())) {
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        player.sendMessage(mm(cfg.messages.notWhitelistedBackend, target, player.getUsername()));
        return;
      }
    }

    // Any managed server (including primary when not first-join):
    // If offline, start it, deny immediate connect, message the player, and queue auto-send.
    if (!running) {
      if (!hasFallback) {
        try {
          log.info("Intercepting initial connect to offline [{}]; starting process for {}", target, player.getUsername());
          mgr.start(target);
        } catch (IOException ex) {
          log.error("Failed to start server {}", target, ex);
          event.setResult(ServerPreConnectEvent.ServerResult.denied());
          player.disconnect(mm(cfg.messages.startFailed, target, player.getUsername()));
          scheduleStopAllIfProxyEmpty();
          return;
        }

        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        player.disconnect(Component.text(kickMessage));
        scheduleStopAllIfProxyEmpty();
        return;
      }

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
    lastKnownServer.put(e.getPlayer().getUniqueId(), joined);

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
    String last = lastKnownServer.remove(id);
    if (last != null) {
      scheduleStopIfServerEmpty(last);
    }

    // If the proxy became empty, also arm the stop-all as a safety net
    scheduleStopAllIfProxyEmpty();
  }

  // -------------------- Stop helpers --------------------
  /** Schedule a stop for this backend if it currently has zero players. */
  private void scheduleStopIfServerEmpty(String serverName) {
    if (!mgr.isKnown(serverName)) return;
    if (countPlayersOn(serverName) > 0) return;
    if (pendingServerStop.containsKey(serverName)) return;
    if (mgr.isHoldActive(serverName)) {
      log.info("[{}] hold active; skipping auto-stop schedule.", serverName);
      return;
    }

    int delay = Math.max(0, cfg.stopGraceSeconds);
    log.info("[{}] no players online; scheduling stop in {}s", serverName, delay);

    ScheduledTask task = proxy.getScheduler().buildTask(pluginOwner, () -> {
      pendingServerStop.remove(serverName);
      if (countPlayersOn(serverName) > 0) {
        log.info("[{}] stop skipped; players rejoined.", serverName);
        return;
      }
      if (mgr.isHoldActive(serverName)) {
        log.info("[{}] auto-stop canceled; hold still active.", serverName);
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

        log.info("Proxy empty for {}s; stopping idle managed servers...", delaySeconds);
        for (String name : cfg.servers.keySet()) {
          if (mgr.isHoldActive(name)) {
            log.info("[{}] hold active; skipping proxy-empty stop.", name);
            continue;
          }
          if (!mgr.isRunning(name)) continue;
          try {
            mgr.stop(name);
            isReadyCache.put(name, Boolean.FALSE);
          } catch (Exception ex) {
            log.error("Failed to stop server {} during proxy-empty sweep", name, ex);
          }
        }
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

  // -------------------- Indefinite hold auto-restart --------------------
  private void refreshHoldRestart(String serverName) {
    var sc = cfg.servers.get(serverName);
    if (sc == null) {
      cancelHoldRestart(serverName);
      return;
    }
    long minutes = sc.autoRestartHoldMinutes == null ? 0L : sc.autoRestartHoldMinutes;
    if (minutes <= 0) {
      cancelHoldRestart(serverName);
      return;
    }
    if (!mgr.isRunning(serverName)) {
      cancelHoldRestart(serverName);
      return;
    }
    if (mgr.holdRemainingSeconds(serverName) != Long.MAX_VALUE) {
      cancelHoldRestart(serverName);
      return;
    }

    long intervalMs = minutesToMillis(minutes);
    if (intervalMs <= 0L) {
      cancelHoldRestart(serverName);
      return;
    }

    long now = System.currentTimeMillis();
    RestartSchedule existing = holdRestartTasks.get(serverName);
    if (existing != null && existing.restartAtMs > now) {
      return; // already scheduled
    }
    scheduleHoldRestart(serverName, intervalMs);
  }

  private void scheduleHoldRestart(String serverName, long intervalMs) {
    cancelHoldRestart(serverName);

    long now = System.currentTimeMillis();
    long restartAt = now + intervalMs;
    RestartSchedule rs = new RestartSchedule(restartAt);
    holdRestartTasks.put(serverName, rs);

    long warn1mDelay = intervalMs - Duration.ofMinutes(1).toMillis();
    if (warn1mDelay > 0) {
      rs.warn1m = proxy.getScheduler().buildTask(pluginOwner, () ->
          notifyPlayers(serverName, mm(cfg.messages.holdRestartWarning1m, serverName, "")))
          .delay(Duration.ofMillis(warn1mDelay)).schedule();
    }

    long warn5sDelay = intervalMs - Duration.ofSeconds(5).toMillis();
    if (warn5sDelay > 0) {
      rs.warn5s = proxy.getScheduler().buildTask(pluginOwner, () ->
          notifyPlayers(serverName, mm(cfg.messages.holdRestartWarning5s, serverName, "")))
          .delay(Duration.ofMillis(warn5sDelay)).schedule();
    }

    rs.restart = proxy.getScheduler().buildTask(pluginOwner, () -> {
      notifyPlayers(serverName, mm(cfg.messages.holdRestartNow, serverName, ""));
      try {
        log.info("[{}] auto-restart triggered (indefinite hold). Stopping...", serverName);
        mgr.stop(serverName);
        isReadyCache.put(serverName, Boolean.FALSE);
      } catch (Exception ex) {
        log.error("Failed to stop {} during auto-restart", serverName, ex);
      }
      try {
        mgr.start(serverName);
        log.info("[{}] auto-restart start issued.", serverName);
      } catch (IOException ex) {
        log.error("Failed to start {} during auto-restart", serverName, ex);
      } finally {
        holdRestartTasks.remove(serverName);
      }
    }).delay(Duration.ofMillis(intervalMs)).schedule();

    log.info("[{}] scheduled auto-restart in {} minutes (indefinite hold).", serverName, intervalMs / 60000L);
  }

  private void cancelHoldRestart(String serverName) {
    RestartSchedule rs = holdRestartTasks.remove(serverName);
    if (rs == null) return;
    if (rs.warn1m != null) rs.warn1m.cancel();
    if (rs.warn5s != null) rs.warn5s.cancel();
    if (rs.restart != null) rs.restart.cancel();
  }

  private void notifyPlayers(String serverName, Component message) {
    if (message == null) return;
    proxy.getAllPlayers().forEach(p -> p.getCurrentServer().ifPresent(cs -> {
      if (cs.getServerInfo().getName().equals(serverName)) {
        p.sendMessage(message);
      }
    }));
  }

  private long minutesToMillis(long minutes) {
    if (minutes <= 0) return 0L;
    long max = Long.MAX_VALUE / 60000L;
    if (minutes > max) return Long.MAX_VALUE;
    return minutes * 60000L;
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
          if (mgr.isHoldActive(serverName)) {
            log.info("[{}] auto-send timeout hit but hold is active; leaving running.", serverName);
            return;
          }
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

  private boolean hasNetworkAccess(Player player) {
    if (isNetworkBanned(player, true)) {
      return false;
    }
    if (!whitelistEnabled()) return true;
    UUID uuid = player.getUniqueId();
    String name = player.getUsername();
    if (whitelist.isWhitelisted(uuid, name)) {
      mirrorToVanilla(uuid, name);
      return true;
    }
    if (vanillaWhitelist != null && !vanillaBypassServers.isEmpty()) {
      for (String server : vanillaBypassServers) {
        if (!vanillaWhitelist.tracksServer(server)) continue;
        if (!vanillaWhitelist.isWhitelisted(server, uuid, name)) continue;
        try {
          whitelist.add(uuid, name);
          mirrorToVanilla(uuid, name);
        } catch (IOException ex) {
          log.warn("Failed to mirror vanilla whitelist entry for {}", name, ex);
        }
        return true;
      }
    }
    return false;
  }

  private void handleNotWhitelisted(Player player, boolean disconnect) {
    Component msg;
    if (whitelist != null && whitelist.enabled()) {
      var pc = whitelist.issueCode(player.getUniqueId(), player.getUsername());
      String url = buildWhitelistUrl(pc.code(), player.getUsername());
      String text = whitelist.kickMessage(url, pc.code());
      msg = Component.text(text);
      log.info("Denied {} (not on network whitelist). Issued code {}", player.getUsername(), pc.code());
    } else {
      msg = Component.text("You are not permitted to join right now.");
    }
    if (disconnect) {
      player.disconnect(msg);
    } else {
      player.sendMessage(msg);
    }
  }

  private String buildWhitelistUrl(String code, String username) {
    if (!whitelistEnabled()) return "";
    String base = whitelistCfg.baseUrl;
    if (base == null || base.isBlank()) {
      base = "http://" + whitelistCfg.bind + ":" + whitelistCfg.port;
    }
    return base;
  }

  private boolean whitelistEnabled() {
    return whitelist != null && whitelist.enabled();
  }

  private boolean isNetworkBanned(Player player, boolean disconnect) {
    if (networkBans == null || !networkBans.enabled()) return false;
    var entry = networkBans.lookup(player.getUniqueId(), player.getUsername());
    if (entry.isEmpty()) return false;
    Component msg = mmReason(firstNonBlank(cfg.messages.networkBanned, "<red>You are banned from this network.</red>"),
        player.getUsername(), entry.get().reason());
    if (disconnect) {
      player.disconnect(msg);
    } else {
      player.sendMessage(msg);
    }
    log.info("Denied {} (network banned).", player.getUsername());
    return true;
  }

  public void mirrorToVanilla(UUID uuid, String name) {
    if (vanillaWhitelist == null || mirrorNetworkWhitelistServers.isEmpty()) return;
    for (String server : mirrorNetworkWhitelistServers) {
      vanillaWhitelist.ensureWhitelisted(server, uuid, name);
    }
    if (name == null || name.isBlank()) return;
    String cmd = "whitelist add " + name;
    for (String server : mirrorNetworkWhitelistServers) {
      if (!mgr.isRunning(server)) continue;
      if (!mgr.sendCommand(server, cmd)) {
        log.warn("Failed to dispatch '{}' to {} after whitelist sync", cmd, server);
      }
    }
  }

  public void removeFromMirrors(UUID uuid, String name) {
    if (vanillaWhitelist == null || mirrorNetworkWhitelistServers.isEmpty()) return;
    for (String server : mirrorNetworkWhitelistServers) {
      try {
        vanillaWhitelist.removeEntry(server, uuid, name);
      } catch (IOException ex) {
        log.warn("Failed to remove whitelist entry from {}", server, ex);
      }
    }
    if (name == null || name.isBlank()) return;
    String cmd = "whitelist remove " + name;
    for (String server : mirrorNetworkWhitelistServers) {
      if (!mgr.isRunning(server)) continue;
      if (!mgr.sendCommand(server, cmd)) {
        log.warn("Failed to dispatch '{}' to {} after whitelist removal", cmd, server);
      }
    }
  }

  private String resolveKickMessage(Config.ForcedHost hostCfg) {
    if (hostCfg != null && hostCfg.kickMessage != null && !hostCfg.kickMessage.isBlank()) {
      return hostCfg.kickMessage;
    }
    return cfg.kickMessage;
  }

  private String resolveTrackedServer(Config.ForcedHost hostCfg, String normalizedHost) {
    String explicit = (hostCfg != null) ? sanitizeServerName(hostCfg.server) : null;
    if (explicit != null) return explicit;

    String viaVelocity = findVelocityForcedHost(normalizedHost);
    if (viaVelocity != null) return viaVelocity;

    String primary = sanitizeServerName(cfg.primaryServerName());
    return primary;
  }

  private String findVelocityForcedHost(String normalizedHost) {
    if (normalizedHost == null) return null;
    Map<String, List<String>> forced = proxy.getConfiguration().getForcedHosts();
    if (forced == null || forced.isEmpty()) return null;

    List<String> direct = forced.get(normalizedHost);
    if (direct == null) {
      for (var entry : forced.entrySet()) {
        String normalizedKey = normalizeHost(entry.getKey());
        if (normalizedKey != null && normalizedKey.equals(normalizedHost)) {
          direct = entry.getValue();
          break;
        }
      }
    }
    if (direct == null || direct.isEmpty()) return null;

    for (String candidate : direct) {
      String sanitized = sanitizeServerName(candidate);
      if (sanitized != null) return sanitized;
    }
    return null;
  }

  private static String sanitizeServerName(String name) {
    if (name == null) return null;
    String trimmed = name.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String normalizeHost(String host) {
    if (host == null) return null;
    String value = host.trim();
    int nulIndex = value.indexOf('\0');
    if (nulIndex >= 0) {
      value = value.substring(0, nulIndex);
    }
    int slashIndex = value.indexOf('/');
    if (slashIndex >= 0) {
      value = value.substring(0, slashIndex);
    }
    value = value.trim();
    if (value.isEmpty()) return null;
    int colon = value.indexOf(':');
    if (colon >= 0) value = value.substring(0, colon);
    if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
    if (value.isEmpty()) return null;
    return value.toLowerCase(Locale.ROOT);
  }

  private static String normalizePlaceholders(String s) {
    if (s == null) return "";
    return s
        .replace("{server}", "<server>").replace("(server)", "<server>")
        .replace("{player}", "<player>").replace("(player)", "<player>")
        .replace("{state}", "<state>").replace("(state)", "<state>")
        .replace("{reason}", "<reason>").replace("(reason)", "<reason>");
  }

  private static String firstNonBlank(String... options) {
    if (options == null) return "";
    for (String opt : options) {
      if (opt != null && !opt.isBlank()) return opt;
    }
    return "";
  }

  private static Component mm(String template, String server, String player) {
    String t = normalizePlaceholders(template);
    return MiniMessage.miniMessage().deserialize(t,
        Placeholder.unparsed("server", server == null ? "" : server),
        Placeholder.unparsed("player", player == null ? "" : player));
  }

  private static Component mmReason(String template, String player, String reason) {
    String t = normalizePlaceholders(template);
    return MiniMessage.miniMessage().deserialize(t,
        Placeholder.unparsed("player", player == null ? "" : player),
        Placeholder.unparsed("reason", reason == null ? "" : reason));
  }

  public void shutdown() {
    ScheduledTask stopAll = pendingStopAll;
    if (stopAll != null) {
      stopAll.cancel();
      pendingStopAll = null;
    }
    pendingServerStop.values().forEach(ScheduledTask::cancel);
    pendingServerStop.clear();
    pendingConnect.values().forEach(ScheduledTask::cancel);
    pendingConnect.clear();
    pendingConnectStartMs.clear();
    waitingTarget.clear();
    readyNotifyOnce.clear();
    lastKnownServer.clear();
    holdRestartTasks.values().forEach(rs -> {
      if (rs.warn1m != null) rs.warn1m.cancel();
      if (rs.warn5s != null) rs.warn5s.cancel();
      if (rs.restart != null) rs.restart.cancel();
    });
    holdRestartTasks.clear();
    heartbeatTask.cancel();
  }
}
