package dev.e1ixyz.servermanager;

import dev.e1ixyz.servermanager.commands.ServerManagerCmd;
import dev.e1ixyz.servermanager.commands.ServerMenuCmd;
import dev.e1ixyz.servermanager.listeners.PlayerEvents;
import dev.e1ixyz.servermanager.maps.MapsHttpServer;
import dev.e1ixyz.servermanager.moderation.ModerationService;
import dev.e1ixyz.servermanager.preferences.JoinPreferenceService;
import dev.e1ixyz.servermanager.discord.DiscordBot;
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
  private DiscordBot discordBot;
  private MapsHttpServer mapsHttpServer;
  private VanillaWhitelistChecker vanillaWhitelist;
  private JoinPreferenceService joinPreferences;
  private PlayerEvents playerEvents;
  private ServerManagerCmd rootCommand;
  private ServerMenuCmd serverMenuCommand;
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
      this.rootCommand.updateState(processManager, config, whitelistService, vanillaWhitelist, moderation, joinPreferences);
      cm.register(meta, rootCommand);
      registerModerationCommands();

      // Reskinned /server chat menu: replace Velocity's built-in when enabled.
      if (config.serverMenu != null && config.serverMenu.enabled) {
        if (cm.hasCommand("server")) cm.unregister("server");
        this.serverMenuCommand = new ServerMenuCmd(this, proxy, logger);
        this.serverMenuCommand.updateState(config);
        cm.register(cm.metaBuilder("server").plugin(this).build(), serverMenuCommand);
        logger.info("ServerManager: /server menu enabled (replaced built-in).");
      }

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
      if (discordBot != null) {
        discordBot.stop();
      }
      if (mapsHttpServer != null) {
        mapsHttpServer.stop();
      }
      if (playerEvents != null) {
        playerEvents.shutdown();
        proxy.getEventManager().unregisterListeners(this);
        playerEvents = null;
      }
      if (processManager != null && !isCraftyModeEnabled()) {
        // In Crafty mode the backends are owned by Crafty and must survive a proxy restart.
        processManager.stopAllGracefully();
      }
      shutdownModeration();
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
      if (discordBot != null) {
        discordBot.stop();
        discordBot = null;
      }
      if (mapsHttpServer != null) {
        mapsHttpServer.stop();
        mapsHttpServer = null;
      }
      if (playerEvents != null) {
        playerEvents.shutdown();
        playerEvents = null;
      }
      proxy.getEventManager().unregisterListeners(this);
      shutdownModeration();

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
    this.joinPreferences = new JoinPreferenceService(logger, dataDir);
    shutdownModeration();
    this.moderation = new ModerationService(newConfig.moderation, logger, dataDir);
    this.discordBot = new DiscordBot(newConfig.discord, System.getenv("SM_DISCORD_TOKEN"), whitelistService, logger);
    this.discordBot.start();
    this.mapsHttpServer = new MapsHttpServer(newConfig.maps, logger);
    this.mapsHttpServer.start();
    this.playerEvents = new PlayerEvents(this, proxy, newConfig, processManager, logger,
        whitelistService, vanillaWhitelist, moderation, joinPreferences);
    proxy.getEventManager().register(this, playerEvents);
    registerModerationCommands();
    if (rootCommand != null) {
      rootCommand.updateState(processManager, newConfig, whitelistService, vanillaWhitelist, moderation, joinPreferences);
    }
    if (serverMenuCommand != null) {
      serverMenuCommand.updateState(newConfig);
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

  /** True when Crafty Controller owns the backend processes (ServerManager defers lifecycle). */
  public boolean isCraftyModeEnabled() {
    return config != null
        && config.compatibility != null
        && config.compatibility.crafty != null
        && config.compatibility.crafty.enabled;
  }

  /**
   * Whether a server should be considered "up". In Crafty mode this is ping readiness (Crafty owns
   * the process, so the process handle is absent); otherwise it is the owned process handle.
   */
  public boolean isServerUp(String server) {
    if (processManager == null) return false;
    if (isCraftyModeEnabled()) {
      return playerEvents != null && playerEvents.isServerReady(server);
    }
    return processManager.isRunning(server);
  }

  /** Latest backend version (x.y.z) seen via ping, or null if unknown. Used by the /server menu. */
  public String serverVersion(String server) {
    return playerEvents != null ? playerEvents.serverVersion(server) : null;
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
    boolean ready = playerEvents != null && playerEvents.isServerReady(server);
    // In Crafty mode the process handle is absent; report ping readiness as "running".
    boolean running = isCraftyModeEnabled() ? ready : processManager.isRunning(server);
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

  private synchronized void shutdownModeration() {
    if (moderation == null) {
      return;
    }
    try {
      moderation.shutdown();
    } catch (Exception ex) {
      logger.warn("Failed to shutdown moderation service cleanly", ex);
    } finally {
      moderation = null;
    }
  }
}
