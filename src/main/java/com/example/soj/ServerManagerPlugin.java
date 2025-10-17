package com.example.soj;

import com.example.soj.listeners.PlayerEvents;
import com.example.soj.commands.ServerManagerCmd;
import com.example.soj.whitelist.WhitelistHttpServer;
import com.example.soj.whitelist.WhitelistService;
import com.example.soj.whitelist.VanillaWhitelistChecker;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
      this.rootCommand = new ServerManagerCmd(this, processManager, config, logger);
      cm.register(meta, rootCommand);

      logger.info("ServerManager initialized. Primary: {}", config.primaryServerName());
    } catch (Exception ex) {
      logger.error("Failed to initialize ServerManager", ex);
    }
  }

  @Subscribe
  public void onShutdown(ProxyShutdownEvent e) {
    try {
      logger.info("Proxy shutting down; stopping any running managed servers...");
      if (whitelistHttpServer != null) {
        whitelistHttpServer.stop();
      }
      if (playerEvents != null) {
        playerEvents.shutdown();
        proxy.getEventManager().unregisterListener(playerEvents);
        playerEvents = null;
      }
      if (processManager != null) {
        processManager.stopAllGracefully();
      }
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
        proxy.getEventManager().unregisterListener(playerEvents);
        playerEvents = null;
      }
      if (processManager != null) {
        processManager.stopAllGracefully();
      }

      initializeRuntime();
      logger.info("ServerManager reloaded. Primary: {}", config.primaryServerName());
      return true;
    } catch (Exception ex) {
      logger.error("Failed to reload ServerManager", ex);
      return false;
    }
  }

  private void initializeRuntime() throws Exception {
    this.config = Config.loadOrCreateDefault(dataDir.resolve("config.yml"), logger);
    this.processManager = new ServerProcessManager(config, logger);
    this.whitelistService = new WhitelistService(config.whitelist, logger, dataDir);
    this.vanillaWhitelist = new VanillaWhitelistChecker(config, logger);
    this.whitelistHttpServer = new WhitelistHttpServer(config.whitelist, logger, whitelistService);
    this.whitelistHttpServer.start();
    this.playerEvents = new PlayerEvents(this, proxy, config, processManager, logger, whitelistService, vanillaWhitelist);
    proxy.getEventManager().register(this, playerEvents);
    if (rootCommand != null) {
      rootCommand.updateState(processManager, config);
    }
  }
}
