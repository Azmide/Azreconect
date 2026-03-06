package org.AzSofware.azreconet;

import com.velocitypowered.api.command.CommandMeta;
import org.AzSofware.azreconet.command.QueueCommand;
import org.AzSofware.azreconet.config.ConfigManager;
import org.AzSofware.azreconet.listener.ConnectionListener;
import org.AzSofware.azreconet.manager.QueueManager;
import org.AzSofware.azreconet.manager.ReconnectManager;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id          = "areconet",
        name        = "Areconet",
        version     = "1.0.0",
        description = "Auto-reconnect system for Velocity proxy",
        authors     = {"Areconet"}
)
public class Azreconet {

    private final ProxyServer proxy;
    private final Logger      logger;
    private final Path        dataDirectory;

    private ConfigManager configManager;
    private ReconnectManager reconnectManager;
    private QueueManager queueManager;

    @Inject
    public Azreconet(ProxyServer proxy, Logger logger,
                    @DataDirectory Path dataDirectory) {
        this.proxy         = proxy;
        this.logger        = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info("======================================");
        logger.info("  Areconet v1.0.0 – Initializing...  ");
        logger.info("======================================");

        try {
            configManager = new ConfigManager(dataDirectory, logger);
            configManager.load();

            queueManager = new QueueManager (proxy, logger, configManager, this);
            reconnectManager = new ReconnectManager(proxy, logger, configManager, this, queueManager);

            CommandMeta meta = proxy.getCommandManager()
                    .metaBuilder("queue")
                    .aliases("q", "joinserver")   // alias opsional
                    .plugin(this)
                    .build();
            proxy.getCommandManager().register(meta, new QueueCommand(configManager, queueManager, proxy));
            logger.info("[Areconet] Command /queue berhasil didaftarkan.");

            ConnectionListener connectionListener =
                    new ConnectionListener(proxy, logger, configManager, reconnectManager, queueManager);
            proxy.getEventManager().register(this, connectionListener);

            logger.info("[Areconet] Plugin berhasil dimuat!");
            logger.info("[Areconet] Hub server     : {}", configManager.getHubServer());
            logger.info("[Areconet] Monitored servers: {}", configManager.getMonitoredServers().keySet());

        } catch (Exception e) {
            logger.error("[Areconet] Terjadi kesalahan saat inisialisasi plugin!", e);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("[Areconet] Proxy shutdown – membersihkan resource...");

        try {
            if (reconnectManager != null) {
                reconnectManager.shutdown();
                queueManager.shutdown();
            }
            logger.info("[Areconet] Plugin berhasil dimatikan.");
        } catch (Exception e) {
            logger.error("[Areconet] Error saat shutdown plugin!", e);
        }
    }
}
