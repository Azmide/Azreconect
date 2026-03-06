package org.AzSofware.azreconet.manager;

import org.AzSofware.azreconet.config.ConfigManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReconnectManager {

    private final ProxyServer   proxy;
    private final Logger        logger;
    private final ConfigManager config;
    private final Object        pluginInstance;
    private final QueueManager queueManager;

    private final ConcurrentHashMap<UUID, String> pendingReconnect = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, AtomicBoolean>  serverOfflineFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledTask>  pingTasks          = new ConcurrentHashMap<>();

    public ReconnectManager(ProxyServer proxy, Logger logger,
                            ConfigManager config, Object pluginInstance, QueueManager queueManager) {
        this.proxy          = proxy;
        this.logger         = logger;
        this.config         = config;
        this.pluginInstance = pluginInstance;
        this.queueManager   = queueManager;

        config.getMonitoredServers().keySet()
                .forEach(name -> serverOfflineFlags.put(name, new AtomicBoolean(false)));
    }

    public void markForReconnect(UUID playerId, String fromServer) {
        pendingReconnect.put(playerId, fromServer);
        logger.info("[Areconet] {} ditandai untuk reconnect ke '{}'.", playerId, fromServer);

        AtomicBoolean flag = serverOfflineFlags.computeIfAbsent(fromServer, k -> new AtomicBoolean(false));
        if (flag.compareAndSet(false, true)) {
            startPingScheduler(fromServer);
        }
    }

    public void removeFromReconnect(UUID playerId) {
        String removed = pendingReconnect.remove(playerId);
        if (removed != null) {
            logger.info("[Areconet] {} dihapus dari daftar reconnect.", playerId);
        }
    }

    public boolean isPendingReconnect(UUID playerId) {
        return pendingReconnect.containsKey(playerId);
    }

    public Optional<String> getTargetServer(UUID playerId) {
        return Optional.ofNullable(pendingReconnect.get(playerId));
    }

    public void shutdown() {
        pingTasks.values().forEach(ScheduledTask::cancel);
        pingTasks.clear();
        pendingReconnect.clear();
        serverOfflineFlags.clear();
        logger.info("[Areconet] ReconnectManager dimatikan.");
    }

    private void startPingScheduler(String serverName) {
        long interval = config.getPingIntervalSeconds();
        logger.info("[Areconet] Mulai memantau '{}' setiap {} detik.", serverName, interval);

        ScheduledTask task = proxy.getScheduler()
                .buildTask(pluginInstance, () -> checkServerStatus(serverName))
                .repeat(interval, TimeUnit.SECONDS)
                .schedule();

        pingTasks.put(serverName, task);
    }

    private void stopPingScheduler(String serverName) {
        ScheduledTask task = pingTasks.remove(serverName);
        if (task != null) {
            task.cancel();
            logger.info("[Areconet] Scheduler ping '{}' dihentikan.", serverName);
        }
    }

    private void checkServerStatus(String serverName) {
        Optional<RegisteredServer> serverOpt = proxy.getServer(serverName);
        if (serverOpt.isEmpty()) {
            logger.warn("[Areconet] Server '{}' tidak terdaftar di Velocity!", serverName);
            return;
        }

        serverOpt.get().ping().whenComplete((result, error) -> {
            if (error != null) {
                logger.debug("[Areconet] '{}' masih offline: {}", serverName, error.getMessage());
                return;
            }

            logger.info("[Areconet] '{}' terdeteksi online! Memulai reconnect.", serverName);
            AtomicBoolean flag = serverOfflineFlags.get(serverName);
            if (flag != null) flag.set(false);

            stopPingScheduler(serverName);
            reconnectPlayersFor(serverName);
        });
    }

    private void reconnectPlayersFor(String serverName) {
        Optional<RegisteredServer> targetOpt = proxy.getServer(serverName);
        if (targetOpt.isEmpty()) {
            logger.error("[Areconet] Target reconnect '{}' tidak ditemukan!", serverName);
            return;
        }

        RegisteredServer target    = targetOpt.get();
        long             delay     = config.getReconnectDelaySeconds();

        pendingReconnect.entrySet().stream()
                .filter(e -> e.getValue().equals(serverName))
                .map(Map.Entry::getKey)
                .forEach(playerId ->
                        proxy.getScheduler()
                                .buildTask(pluginInstance, () -> sendPlayerToServer(playerId, target))
                                .delay(delay, TimeUnit.SECONDS)
                                .schedule()
                );
    }

    private void sendPlayerToServer(UUID playerId, RegisteredServer target) {
        String fromServer = pendingReconnect.remove(playerId);
        if (fromServer == null) return;

        Optional<Player> playerOpt = proxy.getPlayer(playerId);
        if (playerOpt.isEmpty()) {
            logger.info("[Areconet] Player {} sudah offline, skip reconnect.", playerId);
            return;
        }

        Player player = playerOpt.get();

        if (player.hasPermission("areconet.bypass")) {
            String msg = config.format(config.getMsgBypassReconnect());
            player.sendMessage(Component.text(msg));
            logger.info("[Areconet] '{}' bypass reconnect.", player.getUsername());
            return;
        }

        String displayName = config.getDisplayName(target.getServerInfo().getName());
        String msg = config.formatServer(config.getMsgServerOnline(), displayName);
        player.sendMessage(Component.text(msg));

        // Gunakan queue jika aktif, langsung kirim jika tidak
        if (config.isQueueEnabled()) {
            queueManager.addToQueue(player, target.getServerInfo().getName());
        } else {
            player.createConnectionRequest(target).fireAndForget();
        }

        logger.info("[Areconet] '{}' proses reconnect ke '{}'.",
                player.getUsername(), target.getServerInfo().getName());
    }
}