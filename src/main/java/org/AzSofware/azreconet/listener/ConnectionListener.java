package org.AzSofware.azreconet.listener;

import org.AzSofware.azreconet.config.ConfigManager;
import org.AzSofware.azreconet.manager.ReconnectManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * ConnectionListener – Multi-Server
 *
 * Menangani kick dari server manapun yang ada di daftar monitored-servers,
 * bukan hanya satu server saja.
 */
public class ConnectionListener {

    private final ProxyServer      proxy;
    private final Logger           logger;
    private final ConfigManager    config;
    private final ReconnectManager reconnectManager;

    public ConnectionListener(ProxyServer proxy, Logger logger,
                              ConfigManager config, ReconnectManager reconnectManager) {
        this.proxy            = proxy;
        this.logger           = logger;
        this.config           = config;
        this.reconnectManager = reconnectManager;
    }

    // ── KickedFromServerEvent ──────────────────────────────────────────────

    @Subscribe(order = PostOrder.EARLY)
    public void onKickedFromServer(KickedFromServerEvent event) {
        Player player     = event.getPlayer();
        String kickedFrom = event.getServer().getServerInfo().getName();

        // Hanya proses jika server ada di daftar monitored
        if (!config.isMonitored(kickedFrom)) return;

        if (player.hasPermission("areconet.bypass")) {
            logger.info("[Areconet] '{}' punya bypass, skip kick handling.", player.getUsername());
            return;
        }

        logger.info("[Areconet] '{}' di-kick dari '{}'. Mengarahkan ke hub.",
                player.getUsername(), kickedFrom);

        Optional<RegisteredServer> hubOpt = proxy.getServer(config.getHubServer());
        if (hubOpt.isEmpty()) {
            logger.error("[Areconet] Hub server '{}' tidak terdaftar!", config.getHubServer());
            return;
        }

        String displayName = config.getDisplayName(kickedFrom);
        player.sendMessage(Component.text(
                "[Areconet] Server " + displayName + " sedang offline. Dipindahkan ke hub...",
                NamedTextColor.YELLOW));

        event.setResult(KickedFromServerEvent.RedirectPlayer.create(
                hubOpt.get(),
                Component.text("Redirected to hub by Areconet", NamedTextColor.GRAY)));

        reconnectManager.markForReconnect(player.getUniqueId(), kickedFrom);
    }

    // ── ServerPostConnectEvent ─────────────────────────────────────────────

    @Subscribe(order = PostOrder.NORMAL)
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player        = event.getPlayer();
        String currentServer = player.getCurrentServer()
                .map(c -> c.getServerInfo().getName()).orElse("");

        // Auto-join hub saat pertama masuk proxy
        if (event.getPreviousServer() == null) {
            sendToHub(player);
            return;
        }

        // Jika player reconnect manual ke server yang ia tunggu, hapus dari list
        reconnectManager.getTargetServer(player.getUniqueId()).ifPresent(target -> {
            if (target.equalsIgnoreCase(currentServer)) {
                reconnectManager.removeFromReconnect(player.getUniqueId());
                logger.info("[Areconet] '{}' reconnect manual ke '{}', dihapus dari queue.",
                        player.getUsername(), currentServer);
            }
        });
    }

    // ── DisconnectEvent ────────────────────────────────────────────────────

    @Subscribe(order = PostOrder.LAST)
    public void onDisconnect(DisconnectEvent event) {
        reconnectManager.removeFromReconnect(event.getPlayer().getUniqueId());
        logger.debug("[Areconet] '{}' disconnect dari proxy.", event.getPlayer().getUsername());
    }

    // ── Helper ─────────────────────────────────────────────────────────────

    private void sendToHub(Player player) {
        Optional<RegisteredServer> hubOpt = proxy.getServer(config.getHubServer());
        if (hubOpt.isEmpty()) {
            logger.error("[Areconet] Hub '{}' tidak terdaftar!", config.getHubServer());
            return;
        }
        logger.info("[Areconet] '{}' join proxy → dikirim ke hub.", player.getUsername());
        player.createConnectionRequest(hubOpt.get()).fireAndForget();
    }
}