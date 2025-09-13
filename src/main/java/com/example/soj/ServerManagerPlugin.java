package com.example.soj;

import com.example.soj.listeners.PlayerEvents;
import com.example.soj.commands.ServerManagerCmd;
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
  authors = {"you"}
)
public final class ServerManagerPlugin {

  private final ProxyServer proxy;
  private final Logger logger;
  private final Path dataDir;

  private Config config;
  private ServerProcessManager processManager;

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
      this.config = Config.loadOrCreateDefault(dataDir.resolve("config.yml"), logger);
      this.processManager = new ServerProcessManager(config, logger);

      // Listeners
      proxy.getEventManager().register(this, new PlayerEvents(this, proxy, config, processManager, logger));

      // Root command: /servermanager and alias /sm
      var cm = proxy.getCommandManager();
      var meta = cm.metaBuilder("servermanager").aliases("sm").plugin(this).build();
      cm.register(meta, new ServerManagerCmd(processManager, config, logger));

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
}
