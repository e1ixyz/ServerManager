package dev.e1ixyz.servermanager;

import dev.e1ixyz.servermanager.commands.ServerManagerCmd;
import dev.e1ixyz.servermanager.listeners.PlayerEvents;
import dev.e1ixyz.servermanager.moderation.ModerationService;
import dev.e1ixyz.servermanager.whitelist.WhitelistHttpServer;
import dev.e1ixyz.servermanager.whitelist.WhitelistService;
import dev.e1ixyz.servermanager.whitelist.VanillaWhitelistChecker;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import dev.e1ixyz.servermanager.commands.ModerationCommands;

@Plugin(
  id = "servermanager",
  name = "ServerManager",
  version = "0.1.0",
  authors = {"e1ixyz"}
)
public final class ServerManagerPlugin {

  private final ProxyServer proxy;
  private final Logger logger;
  private final Path dataDir;

  private Config config;
  private ServerProcessManager processManager;
  private WhitelistService whitelistService;
  private WhitelistHttpServer whitelistHttpServer;
  private VanillaWhitelistChecker vanillaWhitelist;
  private PlayerEvents playerEvents;
  private ServerManagerCmd rootCommand;
  private ModerationService moderation;
  private ModerationCommands moderationCommands;
  private final Set<ManagedLifecycleListener> lifecycleListeners = ConcurrentHashMap.newKeySet();

  @Inject
  public ServerManagerPlugin(ProxyServer proxy, Logger logger, @com.velocitypowered.api.plugin.annotation.DataDirectory Path dataDir) {
    this.proxy = proxy;
    this.logger = logger;
    this.dataDir = dataDir;
  }

  @Subscribe
  public void onInit(ProxyInitializeEvent e) {
    try {
      if (!Files.exists(dataDir)) Files.createDirectories(dataDir);
      migrateOldConfigIfPresent();
      initializeRuntime();

      // Root command: /servermanager and alias /sm
      var cm = proxy.getCommandManager();
      var meta = cm.metaBuilder("servermanager").aliases("sm").plugin(this).build();
      this.rootCommand = new ServerManagerCmd(this, proxy, logger);
      this.rootCommand.updateState(processManager, config, whitelistService, vanillaWhitelist, moderation);
      cm.register(meta, rootCommand);
      registerModerationCommands();

      logger.info("ServerManager initialized. Primary: {}", config.primaryServerName());
    } catch (Exception ex) {
      logger.error("Failed to initialize ServerManager", ex);
    }
  }

  @Subscribe
  public void onShutdown(ProxyShutdownEvent e) {
    try {
      logger.info("Proxy shutting down; stopping any running managed servers...");
      if (processManager != null) {
        processManager.beginShutdown();
      }
      if (whitelistHttpServer != null) {
        whitelistHttpServer.stop();
      }
      if (playerEvents != null) {
        playerEvents.shutdown();
        proxy.getEventManager().unregisterListeners(this);
        playerEvents = null;
      }
      if (processManager != null) {
        processManager.stopAllGracefully();
      }
      moderation = null;
    } catch (Exception ex) {
      logger.error("Error during shutdown stopAll", ex);
    }
  }

  private void migrateOldConfigIfPresent() {
    try {
      Path oldDir = dataDir.getParent().resolve("startonjoin");
      Path oldCfg = oldDir.resolve("config.yml");
      Path newCfg = dataDir.resolve("config.yml");
      if (Files.exists(oldCfg) && !Files.exists(newCfg)) {
        Files.createDirectories(dataDir);
        Files.copy(oldCfg, newCfg, StandardCopyOption.REPLACE_EXISTING);
        logger.info("Migrated config from {} to {}", oldCfg.toString().replace('\\','/'), newCfg.toString().replace('\\','/'));
      }
    } catch (Exception ex) {
      logger.warn("Could not migrate old config", ex);
    }
  }

  public synchronized boolean reload() {
    logger.info("Reloading ServerManager...");
    try {
      if (whitelistHttpServer != null) {
        whitelistHttpServer.stop();
        whitelistHttpServer = null;
      }
      if (playerEvents != null) {
        playerEvents.shutdown();
        playerEvents = null;
      }
      proxy.getEventManager().unregisterListeners(this);

      Config newConfig = Config.loadOrCreateDefault(dataDir.resolve("config.yml"), logger);
      initializeRuntime(newConfig);
      logger.info("ServerManager reloaded. Primary: {}", config.primaryServerName());
      return true;
    } catch (Exception ex) {
      logger.error("Failed to reload ServerManager", ex);
      return false;
    }
  }

  private void initializeRuntime() throws Exception {
    Config newConfig = Config.loadOrCreateDefault(dataDir.resolve("config.yml"), logger);
    initializeRuntime(newConfig);
  }

  private void initializeRuntime(Config newConfig) throws Exception {
    if (processManager == null) {
      processManager = new ServerProcessManager(newConfig, logger, dataDir);
    } else {
      processManager.reload(newConfig);
    }
    this.config = newConfig;
    this.whitelistService = new WhitelistService(newConfig.whitelist, logger, dataDir);
    this.vanillaWhitelist = new VanillaWhitelistChecker(newConfig, logger);
    this.moderation = new ModerationService(newConfig.moderation, logger, dataDir);
    this.whitelistHttpServer = new WhitelistHttpServer(newConfig.whitelist, logger, whitelistService);
    this.whitelistHttpServer.start();
    this.playerEvents = new PlayerEvents(this, proxy, newConfig, processManager, logger,
        whitelistService, vanillaWhitelist, moderation);
    proxy.getEventManager().register(this, playerEvents);
    registerModerationCommands();
    if (rootCommand != null) {
      rootCommand.updateState(processManager, newConfig, whitelistService, vanillaWhitelist, moderation);
    }
  }

  public void mirrorNetworkWhitelistEntry(UUID uuid, String name) {
    if (playerEvents != null) {
      playerEvents.mirrorToVanilla(uuid, name);
    }
  }

  public void removeNetworkWhitelistEntry(UUID uuid, String name) {
    if (playerEvents != null) {
      playerEvents.removeFromMirrors(uuid, name);
    }
  }

  public void sendBackendCommandWhenReady(String server, String command) {
    if (server == null || command == null || command.isBlank()) return;
    if (playerEvents != null) {
      playerEvents.sendBackendCommandWhenReady(server, command);
      return;
    }
    if (processManager != null && processManager.isRunning(server)) {
      if (!processManager.sendCommand(server, command)) {
        logger.warn("Failed to dispatch '{}' to {}", command, server);
      }
    }
  }

  public boolean isServerBridgeCompatibilityEnabled() {
    return config != null
        && config.compatibility != null
        && config.compatibility.serverBridge != null
        && config.compatibility.serverBridge.enabled;
  }

  public CompletableFuture<Boolean> ensureServerReady(String server) {
    if (!isServerBridgeCompatibilityEnabled() || playerEvents == null) {
      return CompletableFuture.completedFuture(Boolean.FALSE);
    }
    return playerEvents.ensureServerReady(server);
  }

  public CompletableFuture<Boolean> connectPlayerWhenReady(Player player, String server) {
    return connectPlayerWhenReady(player, server, null);
  }

  public CompletableFuture<Boolean> connectPlayerWhenReady(Player player, String server, Runnable afterConnect) {
    if (!isServerBridgeCompatibilityEnabled() || playerEvents == null) {
      return CompletableFuture.completedFuture(Boolean.FALSE);
    }
    return playerEvents.connectPlayerWhenReady(player, server, afterConnect);
  }

  public CompletableFuture<Boolean> queuePlayerActionAfterConnect(UUID playerUuid, String server, Runnable action) {
    if (!isServerBridgeCompatibilityEnabled() || playerEvents == null) {
      return CompletableFuture.completedFuture(Boolean.FALSE);
    }
    return playerEvents.queuePlayerActionAfterConnect(playerUuid, server, action);
  }

  public ManagedServerState getManagedServerState(String server) {
    if (server == null || config == null || processManager == null) {
      return null;
    }
    ServerConfig serverCfg = config.servers == null ? null : config.servers.get(server);
    if (serverCfg == null) {
      return null;
    }

    Path workingDir = resolveManagedServerWorkingDirectories().get(server);
    boolean running = processManager.isRunning(server);
    boolean ready = playerEvents != null && playerEvents.isServerReady(server);
    boolean holdActive = processManager.isHoldActive(server);
    long holdRemainingSeconds = processManager.holdRemainingSeconds(server);
    boolean primary = server.equals(config.primaryServerName());
    boolean startOnJoin = Boolean.TRUE.equals(serverCfg.startOnJoin);

    return new ManagedServerState(
        server,
        workingDir,
        running,
        ready,
        holdActive,
        holdRemainingSeconds,
        primary,
        startOnJoin
    );
  }

  public Map<String, ManagedServerState> getManagedServerStates() {
    if (config == null || config.servers == null || config.servers.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, ManagedServerState> states = new LinkedHashMap<>();
    for (String server : config.servers.keySet()) {
      ManagedServerState state = getManagedServerState(server);
      if (state != null) {
        states.put(server, state);
      }
    }
    return states;
  }

  public void registerLifecycleListener(ManagedLifecycleListener listener) {
    if (listener != null) {
      lifecycleListeners.add(listener);
    }
  }

  public void unregisterLifecycleListener(ManagedLifecycleListener listener) {
    if (listener != null) {
      lifecycleListeners.remove(listener);
    }
  }

  public void fireServerReady(String server) {
    for (ManagedLifecycleListener listener : lifecycleListeners) {
      try {
        listener.onServerReady(server);
      } catch (Exception ex) {
        logger.warn("ManagedLifecycleListener threw during onServerReady for {}", server, ex);
      }
    }
  }

  public void firePlayerDelivered(UUID playerUuid, String server) {
    for (ManagedLifecycleListener listener : lifecycleListeners) {
      try {
        listener.onPlayerDelivered(playerUuid, server);
      } catch (Exception ex) {
        logger.warn("ManagedLifecycleListener threw during onPlayerDelivered for {}", playerUuid, ex);
      }
    }
  }

  public synchronized Map<String, Path> resolveManagedServerWorkingDirectories() {
    Map<String, Path> resolved = new LinkedHashMap<>();
    if (config == null || config.servers == null || config.servers.isEmpty()) {
      return resolved;
    }

    Path proxyRoot = dataDir.getParent() != null && dataDir.getParent().getParent() != null
        ? dataDir.getParent().getParent().toAbsolutePath().normalize()
        : Path.of("").toAbsolutePath().normalize();
    for (Map.Entry<String, ServerConfig> entry : config.servers.entrySet()) {
      String name = entry.getKey();
      ServerConfig serverCfg = entry.getValue();
      if (name == null || name.isBlank() || serverCfg == null || serverCfg.workingDir == null || serverCfg.workingDir.isBlank()) {
        continue;
      }

      Path workingDir = Path.of(serverCfg.workingDir);
      if (!workingDir.isAbsolute()) {
        workingDir = proxyRoot.resolve(workingDir).normalize();
      } else {
        workingDir = workingDir.normalize();
      }
      resolved.put(name, workingDir);
    }
    return resolved;
  }

  private void registerModerationCommands() {
    if (moderationCommands == null) {
      moderationCommands = new ModerationCommands(proxy, config, moderation, whitelistService, logger);
      var cm = proxy.getCommandManager();
      cm.register(cm.metaBuilder("ban").plugin(this).build(), moderationCommands);
      cm.register(cm.metaBuilder("stealthban").aliases("sban").plugin(this).build(), moderationCommands);
      cm.register(cm.metaBuilder("ipban").plugin(this).build(), moderationCommands);
      cm.register(cm.metaBuilder("unban").plugin(this).build(), moderationCommands);
      cm.register(cm.metaBuilder("mute").plugin(this).build(), moderationCommands);
      cm.register(cm.metaBuilder("unmute").plugin(this).build(), moderationCommands);
      cm.register(cm.metaBuilder("warn").plugin(this).build(), moderationCommands);
      cm.register(cm.metaBuilder("banlist").plugin(this).build(), moderationCommands);
      cm.register(cm.metaBuilder("mutelist").plugin(this).build(), moderationCommands);
    } else {
      moderationCommands.updateState(config, moderation, whitelistService);
    }
  }
}
