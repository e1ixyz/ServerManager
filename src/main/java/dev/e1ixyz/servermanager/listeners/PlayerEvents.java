package dev.e1ixyz.servermanager.listeners;

import dev.e1ixyz.servermanager.Config;
import dev.e1ixyz.servermanager.ServerManagerPlugin;
import dev.e1ixyz.servermanager.ServerProcessManager;
import dev.e1ixyz.servermanager.moderation.AutoIpBanService;
import dev.e1ixyz.servermanager.moderation.ModerationService;
import dev.e1ixyz.servermanager.whitelist.VanillaWhitelistChecker;
import dev.e1ixyz.servermanager.whitelist.WhitelistService;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
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
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;

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
  private static final int MANAGED_CONNECT_RETRY_ATTEMPTS = 5;
  private static final java.time.format.DateTimeFormatter HHMM = java.time.format.DateTimeFormatter.ofPattern("H:mm");

  private final ServerManagerPlugin pluginOwner;
  private final ProxyServer proxy;
  private final Config cfg;
  private final ServerProcessManager mgr;
  private final Logger log;
  private final WhitelistService whitelist;
  private final VanillaWhitelistChecker vanillaWhitelist;
  private final ModerationService moderation;
  private final AutoIpBanService autoIpBan;
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
  /** Short-lived reconnect target after leaving or disconnecting from the proxy. */
  private final Map<UUID, RecentDisconnect> recentDisconnectServer = new ConcurrentHashMap<>();
  /** Short-lived target for managed backend switches that may disconnect before ServerConnectedEvent fires. */
  private final Map<UUID, RecentDisconnect> recentConnectIntent = new ConcurrentHashMap<>();
  /** Futures waiting for a backend to become ping-ready. */
  private final Map<String, Deque<CompletableFuture<Boolean>>> readyWaiters = new ConcurrentHashMap<>();
  /** Player-scoped actions that should run after the player lands on a specific backend. */
  private final Map<UUID, Map<String, Deque<QueuedArrivalTask>>> queuedArrivalTasks = new ConcurrentHashMap<>();

  /** Lightweight readiness cache: true only after a successful ping. */
  private final Map<String, Boolean> isReadyCache = new ConcurrentHashMap<>();
  /** Auto-restart schedules for indefinite holds. */
  private final Map<String, RestartSchedule> holdRestartTasks = new ConcurrentHashMap<>();
  private final Set<String> holdRestartInProgress = ConcurrentHashMap.newKeySet();
  /** Throttle auto-start attempts for held servers that went offline. */
  private final Map<String, Long> holdAutoStartAttemptMs = new ConcurrentHashMap<>();
  /** Commands queued while a backend process is alive but not ping-ready yet. */
  private final Map<String, Deque<String>> deferredBackendCommands = new ConcurrentHashMap<>();
  private static final int MAX_DEFERRED_BACKEND_COMMANDS = 100;
  private static final long HOLD_AUTOSTART_COOLDOWN_MS = 30_000L;
  @SuppressWarnings("FieldCanBeLocal")
  private final ScheduledTask heartbeatTask;
  private volatile boolean shuttingDown;

  private static final class RestartSchedule {
    final long restartAtMs;
    ScheduledTask warn1m;
    ScheduledTask warn5s;
    ScheduledTask restart;

    RestartSchedule(long restartAtMs) {
      this.restartAtMs = restartAtMs;
    }
  }

  private static final class RecentDisconnect {
    final String serverName;
    final long expiresAtMs;

    private RecentDisconnect(String serverName, long expiresAtMs) {
      this.serverName = serverName;
      this.expiresAtMs = expiresAtMs;
    }
  }

  private static final class QueuedArrivalTask {
    final Runnable action;
    final CompletableFuture<Boolean> future;

    private QueuedArrivalTask(Runnable action, CompletableFuture<Boolean> future) {
      this.action = action;
      this.future = future;
    }
  }

  private static final long CONNECT_INTENT_GRACE_MS = 15_000L;

  public PlayerEvents(
      ServerManagerPlugin pluginOwner,
      ProxyServer proxy,
      Config cfg,
      ServerProcessManager mgr,
      Logger log,
      WhitelistService whitelist,
      VanillaWhitelistChecker vanillaWhitelist,
      ModerationService moderation
  ) {
    this.pluginOwner = pluginOwner;
    this.proxy = proxy;
    this.cfg = cfg;
    this.mgr = mgr;
    this.log = log;
    this.whitelist = whitelist;
    this.vanillaWhitelist = vanillaWhitelist;
    this.moderation = moderation;
    this.autoIpBan = new AutoIpBanService(cfg.autoIpBan, moderation, log);
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
      if (shuttingDown) return;
      for (String name : cfg.servers.keySet()) {
        refreshHoldRestart(name);
        if (!mgr.isRunning(name)) {
          updateReadiness(name, false);
          clearDeferredBackendCommands(name);
          if (mgr.isHoldActive(name)) {
            long now = System.currentTimeMillis();
            long lastAttempt = holdAutoStartAttemptMs.getOrDefault(name, 0L);
            if (now - lastAttempt >= HOLD_AUTOSTART_COOLDOWN_MS) {
              holdAutoStartAttemptMs.put(name, now);
              try {
                log.info("[{}] hold active but server offline; starting...", name);
                mgr.start(name);
              } catch (IOException ex) {
                log.error("Failed to auto-start held server {}", name, ex);
              }
            }
          }
          continue;
        }
        if (countPlayersOn(name) == 0 && !mgr.isHoldActive(name)) {
          scheduleStopIfServerEmpty(name);
        }
        proxy.getServer(name).ifPresent(rs -> rs.ping().whenComplete((ok, err) -> {
          updateReadiness(name, err == null);
        }));
      }
    }).delay(Duration.ofSeconds(1)).repeat(Duration.ofSeconds(2)).schedule();
  }

  // -------------------- MOTD --------------------
  @Subscribe
  public void onPing(ProxyPingEvent e) {
    if (autoIpBan != null && autoIpBan.enabled()) {
      String ip = remoteIp(e.getConnection().getRemoteAddress());
      autoIpBan.record(ip, AutoIpBanService.EventType.PING, null);
    }
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
    failAllQueuedArrivals(joining.getUniqueId());

    if (moderation != null && moderation.enabled()) {
      var ban = moderation.findBan(joining.getUniqueId(), remoteIp(joining));
      if (ban != null) {
        Component msg = buildBanKickMessage(joining, ban);
        e.setResult(LoginEvent.ComponentResult.denied(msg));
        log.info("Denied {} (banned) at login.", joining.getUsername());
        return;
      }
    }

    if (autoIpBan != null && autoIpBan.enabled()) {
      String ip = remoteIp(joining);
      var decision = autoIpBan.record(ip, AutoIpBanService.EventType.CONNECTION, joining.getUsername());
      if (decision.banned() && decision.entry() != null) {
        Component msg = buildBanKickMessage(joining, decision.entry());
        e.setResult(LoginEvent.ComponentResult.denied(msg));
        return;
      }
      if (autoIpBan.isBadUsername(joining.getUsername())) {
        var badDecision = autoIpBan.record(ip, AutoIpBanService.EventType.BAD_USERNAME, joining.getUsername());
        if (badDecision.banned() && badDecision.entry() != null) {
          Component msg = buildBanKickMessage(joining, badDecision.entry());
          e.setResult(LoginEvent.ComponentResult.denied(msg));
          return;
        }
      }
    }

    if (!hasNetworkAccess(joining)) {
      handleNotWhitelisted(joining, true);
      e.setResult(LoginEvent.ComponentResult.denied(Component.empty()));
    }
  }

  // -------------- Chat moderation --------------
  @Subscribe
  public void onChat(PlayerChatEvent e) {
    if (moderation == null || !moderation.enabled()) return;
    Player player = e.getPlayer();
    var mute = moderation.findMute(player.getUniqueId());
    if (mute == null) return;
    Component msg = buildModerationMessage(cfg.messages.mutedMessage, player.getUsername(), mute);
    e.setResult(PlayerChatEvent.ChatResult.denied());
    player.sendMessage(msg);
  }

  @Subscribe
  public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
    String forcedTarget = resolveManagedForcedHostTarget(event.getPlayer());
    String preferredTarget = forcedTarget != null ? forcedTarget : resolveRecentReconnectTarget(event.getPlayer().getUniqueId());
    if (preferredTarget == null) return;

    proxy.getServer(preferredTarget).ifPresentOrElse(event::setInitialServer, () ->
        log.warn("Initial target {} is managed but not registered with Velocity.", preferredTarget));
  }

  // -------------- Gate all connects (/server <target>, menu join, etc.) --------------
  @Subscribe
  public void onServerPreConnect(ServerPreConnectEvent event) {
    RegisteredServer original = event.getOriginalServer();
    RegisteredServer effective = event.getResult().getServer().orElse(original);
    String target = (effective != null) ? effective.getServerInfo().getName() : null;
    Player player = event.getPlayer();
    if (event.getPreviousServer() == null) {
      String preferredTarget = resolveManagedForcedHostTarget(player);
      if (preferredTarget == null) {
        preferredTarget = resolveRecentReconnectTarget(player.getUniqueId());
      }
      if (preferredTarget != null && !preferredTarget.equals(target)) {
        var preferredServer = proxy.getServer(preferredTarget);
        if (preferredServer.isPresent()) {
          effective = preferredServer.get();
          target = preferredTarget;
          event.setResult(ServerPreConnectEvent.ServerResult.allowed(effective));
        } else {
          log.warn("Initial target {} is managed but not registered with Velocity.", preferredTarget);
        }
      }
    }

    if (target == null) return;
    if (!mgr.isKnown(target)) return; // only manage servers present in our config

    if (isBanned(player, true)) {
      failQueuedArrivals(player.getUniqueId(), target);
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      return;
    }
    String primary = cfg.primaryServerName();
    boolean running = mgr.isRunning(target);
    boolean ready = isReady(target);
    boolean startingState = running && !ready;
    boolean isPrimary = target.equals(primary);
    boolean hasFallback = player.getCurrentServer().isPresent();

    Config.ForcedHost hostCfg = player.getVirtualHost()
        .map(addr -> forcedHostOverrides.get(normalizeHost(addr.getHostString()))).orElse(null);
    String kickMessage = resolveKickMessage(hostCfg);

    if (isPrimary) {
      if (!hasNetworkAccess(player)) {
        handleNotWhitelisted(player, true);
        failQueuedArrivals(player.getUniqueId(), target);
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        return;
      }
    } else {
      if (vanillaWhitelist != null
          && vanillaWhitelist.tracksServer(target)
          && vanillaWhitelist.isWhitelistEnabled(target)
          && !vanillaWhitelist.isWhitelisted(target, player.getUniqueId(), player.getUsername())) {
        failQueuedArrivals(player.getUniqueId(), target);
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        player.sendMessage(mm(cfg.messages.notWhitelistedBackend, target, player.getUsername()));
        return;
      }
    }

    String currentManaged = player.getCurrentServer()
        .map(conn -> conn.getServerInfo().getName())
        .filter(mgr::isKnown)
        .orElse(null);
    if (currentManaged != null && !currentManaged.equals(target)) {
      rememberRecentConnectIntent(player.getUniqueId(), target);
    }

    // Any managed server (including primary when not first-join):
    // If offline, start it, deny immediate connect, message the player, and queue auto-send.
    if (!running || startingState) {
      if (!hasFallback) {
        try {
          if (!running) {
            log.info("Intercepting initial connect to offline [{}]; starting process for {}", target, player.getUsername());
            mgr.start(target);
          } else {
            log.info("Intercepting connect to starting [{}]; deferring player {}", target, player.getUsername());
          }
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
        failQueuedArrivals(id, waiting);
      }

      try {
        if (!running) {
          log.info("Intercepting connect to offline [{}]; starting and queueing auto-connect", target);
          mgr.start(target);
        } else {
          log.info("Intercepting connect to starting [{}]; queueing auto-connect", target);
        }
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
    recentDisconnectServer.remove(e.getPlayer().getUniqueId());
    recentConnectIntent.remove(e.getPlayer().getUniqueId());
    if (mgr.isKnown(joined)) {
      pluginOwner.firePlayerDelivered(e.getPlayer().getUniqueId(), joined);
      runQueuedArrivals(e.getPlayer().getUniqueId(), joined);
    }

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
    failAllQueuedArrivals(id);
    String reconnectServer = null;
    String inFlightTarget = resolveRecentTimedTarget(recentConnectIntent, id);
    if (inFlightTarget != null) {
      reconnectServer = inFlightTarget;
    }

    // If they were on a backend, maybe that backend is now empty
    String currentServer = e.getPlayer().getCurrentServer()
        .map(conn -> conn.getServerInfo().getName())
        .orElse(null);
    if (currentServer != null) {
      if (reconnectServer == null && mgr.isKnown(currentServer)) {
        reconnectServer = currentServer;
      }
      scheduleStopIfServerEmpty(currentServer);
    }
    String last = lastKnownServer.remove(id);
    if (reconnectServer == null && last != null) {
      reconnectServer = last;
    }
    if (last != null) {
      scheduleStopIfServerEmpty(last);
    }
    if (reconnectServer != null && mgr.isKnown(reconnectServer)) {
      rememberRecentDisconnect(id, reconnectServer);
    }
    recentConnectIntent.remove(id);

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
        updateReadiness(serverName, false);
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
            updateReadiness(name, false);
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
    if (shuttingDown) {
      cancelHoldRestart(serverName);
      return;
    }
    var sc = cfg.servers.get(serverName);
    if (sc == null) {
      cancelHoldRestart(serverName);
      return;
    }
    String time = sc.autoRestartHoldTime;
    if (time == null || time.isBlank()) {
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

    long delayMs = millisUntil(time);
    if (delayMs <= 0L) {
      cancelHoldRestart(serverName);
      return;
    }

    RestartSchedule existing = holdRestartTasks.get(serverName);
    if (existing != null) return; // already scheduled
    scheduleHoldRestart(serverName, delayMs, time);
  }

  private void scheduleHoldRestart(String serverName, long delayMs, String time) {
    if (shuttingDown) return;
    cancelHoldRestart(serverName);

    long now = System.currentTimeMillis();
    long restartAt = now + delayMs;
    RestartSchedule rs = new RestartSchedule(restartAt);
    holdRestartTasks.put(serverName, rs);

    long warn1mDelay = delayMs - Duration.ofMinutes(1).toMillis();
    if (warn1mDelay > 0) {
      rs.warn1m = proxy.getScheduler().buildTask(pluginOwner, () ->
          notifyPlayers(serverName, mm(cfg.messages.holdRestartWarning1m, serverName, "")))
          .delay(Duration.ofMillis(warn1mDelay)).schedule();
    }

    long warn5sDelay = delayMs - Duration.ofSeconds(5).toMillis();
    if (warn5sDelay > 0) {
      rs.warn5s = proxy.getScheduler().buildTask(pluginOwner, () ->
          notifyPlayers(serverName, mm(cfg.messages.holdRestartWarning5s, serverName, "")))
          .delay(Duration.ofMillis(warn5sDelay)).schedule();
    }

    rs.restart = proxy.getScheduler().buildTask(pluginOwner, () -> {
      if (shuttingDown) {
        holdRestartTasks.remove(serverName);
        return;
      }
      if (!holdRestartInProgress.add(serverName)) {
        return;
      }
      if (shuttingDown) {
        holdRestartTasks.remove(serverName);
        holdRestartInProgress.remove(serverName);
        return;
      }
      notifyPlayers(serverName, mm(cfg.messages.holdRestartNow, serverName, ""));
      try {
        log.info("[{}] auto-restart triggered (indefinite hold). Stopping...", serverName);
        mgr.stop(serverName);
        updateReadiness(serverName, false);
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
        holdRestartInProgress.remove(serverName);
        if (!shuttingDown) {
          long nextDelay = millisUntil(time);
          if (nextDelay > 0) {
            scheduleHoldRestart(serverName, nextDelay, time);
          }
        }
      }
    }).delay(Duration.ofMillis(delayMs)).schedule();

    log.info("[{}] scheduled auto-restart at {} (in {}ms) for indefinite hold.", serverName, time, delayMs);
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

  private long millisUntil(String time) {
    try {
      java.time.LocalTime target = java.time.LocalTime.parse(time.trim(), HHMM);
      java.time.ZoneId zone = java.time.ZoneId.systemDefault();
      java.time.ZonedDateTime now = java.time.ZonedDateTime.now(zone);
      java.time.ZonedDateTime next = now.with(target);
      if (!next.isAfter(now)) {
        next = next.plusDays(1);
      }
      long millis = java.time.Duration.between(now, next).toMillis();
      return millis <= 0 ? 0 : millis;
    } catch (Exception ex) {
      log.warn("Invalid autoRestartHoldTime '{}'", time);
      return 0L;
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
        failQueuedArrivals(id, serverName);
        // Enforce "no backend without players" if nobody reached it
        if (countPlayersOn(serverName) == 0 && mgr.isRunning(serverName)) {
          if (mgr.isHoldActive(serverName)) {
            log.info("[{}] auto-send timeout hit but hold is active; leaving running.", serverName);
            return;
          }
          try {
            log.info("[{}] auto-send timeout; no players joined -> stopping.", serverName);
            mgr.stop(serverName);
            updateReadiness(serverName, false);
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
        failQueuedArrivals(id, serverName);
        return;
      }

      var rs = opt.get();
      rs.ping().whenComplete((ping, err) -> {
        if (err != null) {
          updateReadiness(serverName, false);
          return; // still not ready
        }
        updateReadiness(serverName, true);

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
          attemptManagedConnect(player, rs, serverName, 0);
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

  private boolean isReady(String serverName) {
    return Boolean.TRUE.equals(isReadyCache.get(serverName));
  }

  public boolean isServerReady(String serverName) {
    return isReady(serverName);
  }

  public CompletableFuture<Boolean> ensureServerReady(String serverName) {
    String normalized = sanitizeServerName(serverName);
    if (normalized == null || !mgr.isKnown(normalized) || proxy.getServer(normalized).isEmpty()) {
      return CompletableFuture.completedFuture(Boolean.FALSE);
    }
    if (isReady(normalized)) {
      return CompletableFuture.completedFuture(Boolean.TRUE);
    }

    CompletableFuture<Boolean> future = enqueueReadyWaiter(normalized);
    if (!mgr.isRunning(normalized)) {
      try {
        mgr.start(normalized);
      } catch (IOException ex) {
        log.error("Failed to start server {}", normalized, ex);
        completeReadyWaiters(normalized, false);
      }
    }

    proxy.getServer(normalized).ifPresent(rs -> rs.ping().whenComplete((ping, err) -> {
      if (err == null) {
        updateReadiness(normalized, true);
      }
    }));
    return future;
  }

  public CompletableFuture<Boolean> connectPlayerWhenReady(Player player, String serverName, Runnable afterConnect) {
    String normalized = sanitizeServerName(serverName);
    if (player == null || normalized == null || !mgr.isKnown(normalized)) {
      return CompletableFuture.completedFuture(Boolean.FALSE);
    }

    Player livePlayer = proxy.getPlayer(player.getUniqueId()).orElse(null);
    RegisteredServer registeredServer = proxy.getServer(normalized).orElse(null);
    if (livePlayer == null || registeredServer == null) {
      return CompletableFuture.completedFuture(Boolean.FALSE);
    }

    String currentServer = livePlayer.getCurrentServer()
        .map(connection -> connection.getServerInfo().getName())
        .orElse(null);
    if (currentServer != null && currentServer.equalsIgnoreCase(normalized)) {
      return runArrivalActionNow(afterConnect);
    }

    UUID playerId = livePlayer.getUniqueId();
    String waiting = waitingTarget.get(playerId);
    if (waiting != null && !waiting.equalsIgnoreCase(normalized)) {
      cancelPendingConnect(playerId);
      failQueuedArrivals(playerId, waiting);
    }

    CompletableFuture<Boolean> future = enqueueQueuedArrival(playerId, normalized, afterConnect);
    cancelPendingServerStop(normalized);

    if (isReady(normalized)) {
      cancelPendingConnect(playerId);
      attemptManagedConnect(livePlayer, registeredServer, normalized, 0);
      return future;
    }

    boolean alreadyWaiting = normalized.equalsIgnoreCase(waitingTarget.get(playerId));
    if (!mgr.isRunning(normalized)) {
      try {
        mgr.start(normalized);
      } catch (IOException ex) {
        log.error("Failed to start server {}", normalized, ex);
        livePlayer.sendMessage(mm(cfg.messages.startFailed, normalized, livePlayer.getUsername()));
        failQueuedArrivals(playerId, normalized);
        return future;
      }
    }

    if (!alreadyWaiting) {
      livePlayer.sendMessage(mm(cfg.messages.startingQueued, normalized, livePlayer.getUsername()));
      queueAutoSend(livePlayer, normalized);
    }
    return future;
  }

  public CompletableFuture<Boolean> queuePlayerActionAfterConnect(UUID playerUuid, String serverName, Runnable action) {
    String normalized = sanitizeServerName(serverName);
    if (playerUuid == null || normalized == null || action == null || !mgr.isKnown(normalized)) {
      return CompletableFuture.completedFuture(Boolean.FALSE);
    }

    Player player = proxy.getPlayer(playerUuid).orElse(null);
    if (player == null) {
      return CompletableFuture.completedFuture(Boolean.FALSE);
    }

    String currentServer = player.getCurrentServer()
        .map(connection -> connection.getServerInfo().getName())
        .orElse(null);
    if (currentServer != null && currentServer.equalsIgnoreCase(normalized)) {
      return runArrivalActionNow(action);
    }

    return enqueueQueuedArrival(playerUuid, normalized, action);
  }

  private boolean hasNetworkAccess(Player player) {
    if (isBanned(player, true)) {
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

  private boolean isBanned(Player player, boolean disconnect) {
    if (moderation == null || !moderation.enabled()) return false;
    var entry = moderation.findBan(player.getUniqueId(), remoteIp(player));
    if (entry == null) return false;
    Component msg = buildBanKickMessage(player, entry);
    if (disconnect) {
      player.disconnect(msg);
    } else {
      player.sendMessage(msg);
    }
    log.info("Denied {} (banned).", player.getUsername());
    return true;
  }

  public void mirrorToVanilla(UUID uuid, String name) {
    if (vanillaWhitelist == null || mirrorNetworkWhitelistServers.isEmpty()) return;
    for (String server : mirrorNetworkWhitelistServers) {
      vanillaWhitelist.ensureWhitelisted(server, uuid, name);
    }
    String cmd = (name == null || name.isBlank()) ? "whitelist reload" : ("whitelist add " + name);
    for (String server : mirrorNetworkWhitelistServers) {
      sendBackendCommandWhenReady(server, cmd);
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
    String cmd = (name == null || name.isBlank()) ? "whitelist reload" : ("whitelist remove " + name);
    for (String server : mirrorNetworkWhitelistServers) {
      sendBackendCommandWhenReady(server, cmd);
    }
  }

  public void sendBackendCommandWhenReady(String server, String command) {
    if (server == null || command == null) return;
    String normalized = command.trim();
    if (normalized.isEmpty()) return;
    if (!mgr.isKnown(server) || !mgr.isRunning(server)) return;

    if (isReady(server)) {
      if (!mgr.sendCommand(server, normalized)) {
        log.warn("Failed to dispatch '{}' to {}", normalized, server);
      }
      return;
    }

    Deque<String> queue = deferredBackendCommands.computeIfAbsent(server, k -> new ConcurrentLinkedDeque<>());
    while (queue.size() >= MAX_DEFERRED_BACKEND_COMMANDS) {
      queue.pollFirst();
    }
    queue.offerLast(normalized);
    log.info("[{}] deferred console command until backend is ready: {}", server, normalized);
  }

  private void updateReadiness(String serverName, boolean ready) {
    Boolean previous = isReadyCache.put(serverName, ready);
    boolean wasReady = Boolean.TRUE.equals(previous);
    if (!ready) {
      return;
    }

    flushDeferredBackendCommands(serverName);
    completeReadyWaiters(serverName, true);
    if (!wasReady) {
      pluginOwner.fireServerReady(serverName);
    }
  }

  private CompletableFuture<Boolean> enqueueReadyWaiter(String serverName) {
    CompletableFuture<Boolean> future = new CompletableFuture<>();
    String key = normalizeServerKey(serverName);
    Deque<CompletableFuture<Boolean>> waiters =
        readyWaiters.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());
    waiters.offerLast(future);

    ScheduledTask timeoutTask = proxy.getScheduler().buildTask(pluginOwner, () -> {
      if (removeReadyWaiter(key, future)) {
        future.complete(Boolean.FALSE);
      }
    }).delay(Duration.ofSeconds(START_CONNECT_TIMEOUT_SECONDS)).schedule();

    future.whenComplete((result, error) -> timeoutTask.cancel());
    return future;
  }

  private boolean removeReadyWaiter(String serverKey, CompletableFuture<Boolean> future) {
    Deque<CompletableFuture<Boolean>> waiters = readyWaiters.get(serverKey);
    if (waiters == null) {
      return false;
    }
    boolean removed = waiters.remove(future);
    if (waiters.isEmpty()) {
      readyWaiters.remove(serverKey, waiters);
    }
    return removed;
  }

  private void completeReadyWaiters(String serverName, boolean value) {
    Deque<CompletableFuture<Boolean>> waiters = readyWaiters.remove(normalizeServerKey(serverName));
    if (waiters == null) {
      return;
    }
    CompletableFuture<Boolean> future;
    while ((future = waiters.pollFirst()) != null) {
      future.complete(value);
    }
  }

  private CompletableFuture<Boolean> enqueueQueuedArrival(UUID playerUuid, String serverName, Runnable action) {
    CompletableFuture<Boolean> future = new CompletableFuture<>();
    String key = normalizeServerKey(serverName);
    QueuedArrivalTask task = new QueuedArrivalTask(action == null ? () -> {} : action, future);
    Map<String, Deque<QueuedArrivalTask>> byServer =
        queuedArrivalTasks.computeIfAbsent(playerUuid, ignored -> new ConcurrentHashMap<>());
    Deque<QueuedArrivalTask> tasks = byServer.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());
    tasks.offerLast(task);

    ScheduledTask timeoutTask = proxy.getScheduler().buildTask(pluginOwner, () -> {
      if (removeQueuedArrival(playerUuid, key, task)) {
        future.complete(Boolean.FALSE);
      }
    }).delay(Duration.ofSeconds(START_CONNECT_TIMEOUT_SECONDS)).schedule();

    future.whenComplete((result, error) -> timeoutTask.cancel());
    return future;
  }

  private boolean removeQueuedArrival(UUID playerUuid, String serverKey, QueuedArrivalTask task) {
    Map<String, Deque<QueuedArrivalTask>> byServer = queuedArrivalTasks.get(playerUuid);
    if (byServer == null) {
      return false;
    }
    Deque<QueuedArrivalTask> tasks = byServer.get(serverKey);
    if (tasks == null) {
      return false;
    }
    boolean removed = tasks.remove(task);
    if (tasks.isEmpty()) {
      byServer.remove(serverKey, tasks);
    }
    if (byServer.isEmpty()) {
      queuedArrivalTasks.remove(playerUuid, byServer);
    }
    return removed;
  }

  private Deque<QueuedArrivalTask> takeQueuedArrivals(UUID playerUuid, String serverName) {
    Map<String, Deque<QueuedArrivalTask>> byServer = queuedArrivalTasks.get(playerUuid);
    if (byServer == null) {
      return null;
    }
    Deque<QueuedArrivalTask> tasks = byServer.remove(normalizeServerKey(serverName));
    if (byServer.isEmpty()) {
      queuedArrivalTasks.remove(playerUuid, byServer);
    }
    return tasks;
  }

  private void runQueuedArrivals(UUID playerUuid, String serverName) {
    Deque<QueuedArrivalTask> tasks = takeQueuedArrivals(playerUuid, serverName);
    if (tasks == null || tasks.isEmpty()) {
      return;
    }
    QueuedArrivalTask task;
    while ((task = tasks.pollFirst()) != null) {
      try {
        task.action.run();
        task.future.complete(Boolean.TRUE);
      } catch (Exception ex) {
        log.warn("Post-connect action failed for {} on {}", playerUuid, serverName, ex);
        task.future.complete(Boolean.FALSE);
      }
    }
  }

  private void failQueuedArrivals(UUID playerUuid, String serverName) {
    Deque<QueuedArrivalTask> tasks = takeQueuedArrivals(playerUuid, serverName);
    if (tasks == null) {
      return;
    }
    QueuedArrivalTask task;
    while ((task = tasks.pollFirst()) != null) {
      task.future.complete(Boolean.FALSE);
    }
  }

  private void failAllQueuedArrivals(UUID playerUuid) {
    Map<String, Deque<QueuedArrivalTask>> byServer = queuedArrivalTasks.remove(playerUuid);
    if (byServer == null) {
      return;
    }
    for (Deque<QueuedArrivalTask> tasks : byServer.values()) {
      QueuedArrivalTask task;
      while ((task = tasks.pollFirst()) != null) {
        task.future.complete(Boolean.FALSE);
      }
    }
  }

  private CompletableFuture<Boolean> runArrivalActionNow(Runnable action) {
    CompletableFuture<Boolean> future = new CompletableFuture<>();
    proxy.getScheduler().buildTask(pluginOwner, () -> {
      try {
        if (action != null) {
          action.run();
        }
        future.complete(Boolean.TRUE);
      } catch (Exception ex) {
        log.warn("Immediate post-connect action failed", ex);
        future.complete(Boolean.FALSE);
      }
    }).schedule();
    return future;
  }

  private void attemptManagedConnect(Player player, RegisteredServer targetServer, String serverName, int attempt) {
    UUID playerUuid = player.getUniqueId();
    player.createConnectionRequest(targetServer).connect().whenComplete((result, error) -> {
      if (proxy.getPlayer(playerUuid).isEmpty()) {
        failQueuedArrivals(playerUuid, serverName);
        return;
      }
      if (error != null) {
        if (attempt < MANAGED_CONNECT_RETRY_ATTEMPTS) {
          retryManagedConnect(playerUuid, serverName, attempt + 1);
          return;
        }
        failQueuedArrivals(playerUuid, serverName);
        player.sendMessage(Component.text("Failed to connect to " + serverName + "."));
        log.warn("Managed connect failed for {} -> {}", player.getUsername(), serverName, error);
        return;
      }
      if (result == null || result.isSuccessful()) {
        return;
      }
      if (result.getStatus() == com.velocitypowered.api.proxy.ConnectionRequestBuilder.Status.SERVER_DISCONNECTED
          && attempt < MANAGED_CONNECT_RETRY_ATTEMPTS) {
        retryManagedConnect(playerUuid, serverName, attempt + 1);
        return;
      }
      failQueuedArrivals(playerUuid, serverName);
      result.getReasonComponent().ifPresent(player::sendMessage);
    });
  }

  private void retryManagedConnect(UUID playerUuid, String serverName, int nextAttempt) {
    proxy.getScheduler().buildTask(pluginOwner, () -> {
      if (!hasQueuedArrivals(playerUuid, serverName)) {
        return;
      }
      Player player = proxy.getPlayer(playerUuid).orElse(null);
      RegisteredServer targetServer = proxy.getServer(serverName).orElse(null);
      if (player == null || targetServer == null) {
        failQueuedArrivals(playerUuid, serverName);
        return;
      }
      attemptManagedConnect(player, targetServer, serverName, nextAttempt);
    }).delay(Duration.ofSeconds(2)).schedule();
  }

  private boolean hasQueuedArrivals(UUID playerUuid, String serverName) {
    Map<String, Deque<QueuedArrivalTask>> byServer = queuedArrivalTasks.get(playerUuid);
    if (byServer == null) {
      return false;
    }
    Deque<QueuedArrivalTask> tasks = byServer.get(normalizeServerKey(serverName));
    return tasks != null && !tasks.isEmpty();
  }

  private void flushDeferredBackendCommands(String server) {
    Deque<String> queue = deferredBackendCommands.get(server);
    if (queue == null || queue.isEmpty()) return;
    if (!mgr.isRunning(server) || !isReady(server)) return;

    int sent = 0;
    while (true) {
      String cmd = queue.pollFirst();
      if (cmd == null) break;
      if (!mgr.sendCommand(server, cmd)) {
        queue.offerFirst(cmd);
        break;
      }
      sent++;
    }
    if (queue.isEmpty()) {
      deferredBackendCommands.remove(server, queue);
    }
    if (sent > 0) {
      log.info("[{}] dispatched {} queued console command(s) after startup.", server, sent);
    }
  }

  private void clearDeferredBackendCommands(String server) {
    deferredBackendCommands.remove(server);
  }

  private String normalizeServerKey(String serverName) {
    String normalized = sanitizeServerName(serverName);
    return normalized == null ? "" : normalized.toLowerCase(Locale.ROOT);
  }

  private String resolveKickMessage(Config.ForcedHost hostCfg) {
    if (hostCfg != null && hostCfg.kickMessage != null && !hostCfg.kickMessage.isBlank()) {
      return hostCfg.kickMessage;
    }
    return cfg.kickMessage;
  }

  private String resolveManagedForcedHostTarget(Player player) {
    if (player == null) return null;
    Config.ForcedHost hostCfg = player.getVirtualHost()
        .map(addr -> forcedHostOverrides.get(normalizeHost(addr.getHostString())))
        .orElse(null);
    if (hostCfg == null) return null;

    String target = sanitizeServerName(hostCfg.server);
    if (target == null || !mgr.isKnown(target)) return null;
    return target;
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

  private String resolveRecentReconnectTarget(UUID playerUuid) {
    if (playerUuid == null) return null;
    if (cfg.reconnectWindowSeconds <= 0) return null;
    String recent = resolveRecentTimedTarget(recentDisconnectServer, playerUuid);
    if (recent != null) return recent;

    // Covers fast hub -> backend -> disconnect races where the reconnect begins before
    // DisconnectEvent or ServerConnectedEvent has finalized the remembered backend.
    String intent = resolveRecentTimedTarget(recentConnectIntent, playerUuid);
    if (intent != null) return intent;

    // Fallback for fast reconnects where the new login races the previous DisconnectEvent.
    String inFlight = lastKnownServer.get(playerUuid);
    if (inFlight != null && mgr.isKnown(inFlight)) {
      return inFlight;
    }
    return null;
  }

  private void rememberRecentDisconnect(UUID playerUuid, String serverName) {
    if (playerUuid == null || serverName == null || cfg.reconnectWindowSeconds <= 0) {
      return;
    }
    long expiresAt = System.currentTimeMillis() + (cfg.reconnectWindowSeconds * 1000L);
    recentDisconnectServer.put(playerUuid, new RecentDisconnect(serverName, expiresAt));
  }

  private void rememberRecentConnectIntent(UUID playerUuid, String serverName) {
    if (playerUuid == null || serverName == null) {
      return;
    }
    recentConnectIntent.put(playerUuid, new RecentDisconnect(serverName, System.currentTimeMillis() + CONNECT_INTENT_GRACE_MS));
  }

  private String resolveRecentTimedTarget(Map<UUID, RecentDisconnect> source, UUID playerUuid) {
    RecentDisconnect recent = source.get(playerUuid);
    if (recent == null) {
      return null;
    }
    if (recent.expiresAtMs <= System.currentTimeMillis()) {
      source.remove(playerUuid, recent);
      return null;
    }
    if (recent.serverName == null || !mgr.isKnown(recent.serverName)) {
      source.remove(playerUuid, recent);
      return null;
    }
    return recent.serverName;
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

  private Component buildModerationMessage(String template, String player, ModerationService.Entry entry) {
    String t = normalizePlaceholders(firstNonBlank(template, "<red>You cannot join.</red>"));
    String reason = firstNonBlank(entry.reason(), "No reason specified");
    String expiry = entry.expiresAt() > 0 ? formatDuration(secondsUntil(entry.expiresAt())) : "never";
    return MiniMessage.miniMessage().deserialize(t,
        Placeholder.unparsed("player", player == null ? "" : player),
        Placeholder.unparsed("reason", reason),
        Placeholder.unparsed("expiry", expiry));
  }

  private Component buildBanKickMessage(Player player, ModerationService.Entry entry) {
    if (entry != null && entry.type() == ModerationService.Type.STEALTH_BAN) {
      return Component.text(randomStealthKickMessage());
    }
    return buildModerationMessage(cfg.messages.bannedMessage, player == null ? "" : player.getUsername(), entry);
  }

  private String randomStealthKickMessage() {
    List<String> messages = cfg.messages.stealthBanKickMessages;
    if (messages == null || messages.isEmpty()) {
      messages = Config.Messages.defaultStealthBanKickMessages();
    }
    int idx = ThreadLocalRandom.current().nextInt(messages.size());
    String msg = messages.get(idx);
    if (msg == null || msg.isBlank()) {
      return "Disconnected";
    }
    return msg;
  }

  private long secondsUntil(long millisEpoch) {
    long now = System.currentTimeMillis();
    if (millisEpoch <= now) return 0L;
    return (millisEpoch - now) / 1000L;
  }

  private static String formatDuration(long seconds) {
    if (seconds <= 0L) return "0s";
    long remaining = seconds;
    long days = remaining / 86400L;
    remaining %= 86400L;
    long hours = remaining / 3600L;
    remaining %= 3600L;
    long minutes = remaining / 60L;
    long secs = remaining % 60L;

    StringBuilder sb = new StringBuilder();
    appendUnit(sb, days, 'd');
    appendUnit(sb, hours, 'h');
    appendUnit(sb, minutes, 'm');
    if (sb.length() == 0 || days == 0 && hours == 0 && minutes == 0) {
      appendUnit(sb, secs, 's');
    } else if (secs > 0) {
      appendUnit(sb, secs, 's');
    }
    return sb.toString().trim();
  }

  private static void appendUnit(StringBuilder sb, long value, char suffix) {
    if (value <= 0) return;
    if (sb.length() > 0) sb.append(' ');
    sb.append(value).append(suffix);
  }

  private String remoteIp(Player player) {
    try {
      var addr = player.getRemoteAddress();
      return remoteIp(addr);
    } catch (Exception ignored) {}
    return null;
  }

  private String remoteIp(java.net.SocketAddress addr) {
    if (addr instanceof InetSocketAddress inet && inet.getAddress() != null) {
      return inet.getAddress().getHostAddress();
    }
    return null;
  }

  public void shutdown() {
    shuttingDown = true;
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
    completeAllReadyWaiters(false);
    clearAllQueuedArrivals();
    lastKnownServer.clear();
    recentDisconnectServer.clear();
    holdRestartTasks.values().forEach(rs -> {
      if (rs.warn1m != null) rs.warn1m.cancel();
      if (rs.warn5s != null) rs.warn5s.cancel();
      if (rs.restart != null) rs.restart.cancel();
    });
    holdRestartTasks.clear();
    holdRestartInProgress.clear();
    deferredBackendCommands.clear();
    heartbeatTask.cancel();
  }

  private void completeAllReadyWaiters(boolean value) {
    for (Map.Entry<String, Deque<CompletableFuture<Boolean>>> entry : readyWaiters.entrySet()) {
      Deque<CompletableFuture<Boolean>> waiters = readyWaiters.remove(entry.getKey());
      if (waiters == null) {
        continue;
      }
      CompletableFuture<Boolean> future;
      while ((future = waiters.pollFirst()) != null) {
        future.complete(value);
      }
    }
  }

  private void clearAllQueuedArrivals() {
    for (UUID playerUuid : queuedArrivalTasks.keySet()) {
      failAllQueuedArrivals(playerUuid);
    }
  }
}
