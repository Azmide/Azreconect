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
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * QueueManager
 *
 * Mengelola antrian player yang ingin masuk ke server.
 * - Player priority (areconet.priority) langsung masuk tanpa antri.
 * - Player biasa masuk ke LinkedBlockingDeque, diproses satu per satu
 *   sesuai interval yang dikonfigurasi.
 * - Satu queue & scheduler per server.
 */
public class QueueManager {

    private final ProxyServer   proxy;
    private final Logger        logger;
    private final ConfigManager config;
    private final Object        pluginInstance;

    /**
     * Queue per server: nama server → deque UUID player.
     * Deque digunakan agar player priority bisa dimasukkan di depan (addFirst).
     */
    private final ConcurrentHashMap<String, LinkedBlockingDeque<UUID>> queues       = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledTask>             queueTasks   = new ConcurrentHashMap<>();

    /** Set UUID player yang sedang dalam queue (untuk cek duplikat). */
    private final Set<UUID> inQueue = ConcurrentHashMap.newKeySet();

    public QueueManager(ProxyServer proxy, Logger logger,
                        ConfigManager config, Object pluginInstance) {
        this.proxy          = proxy;
        this.logger         = logger;
        this.config         = config;
        this.pluginInstance = pluginInstance;
    }

    // Public API

    /**
     * Tambahkan player ke antrian server tertentu.
     * Jika player punya priority permission dan skip-queue aktif, langsung kirim.
     *
     * @param player     Player yang akan dikirim
     * @param serverName Nama server tujuan
     */
    public void addToQueue(Player player, String serverName) {
        Optional<RegisteredServer> serverOpt = proxy.getServer(serverName);
        if (serverOpt.isEmpty()) {
            logger.warn("[Areconet] Queue: server '{}' tidak ditemukan!", serverName);
            return;
        }

        // Cegah duplikat
        if (inQueue.contains(player.getUniqueId())) {
            int pos = getPosition(player.getUniqueId(), serverName);
            String msg = config.formatQueue(config.getMsgQueuePosition(), serverName,
                    pos, getQueueSize(serverName));
            player.sendMessage(Component.text(msg));
            return;
        }

        // Priority skip queue
        if (config.isPrioritySkipQueue() && player.hasPermission(config.getPriorityPermission())) {
            String msg = config.formatServer(config.getMsgQueueBypass(), config.getDisplayName(serverName));
            player.sendMessage(Component.text(msg));
            logger.info("[Areconet] '{}' priority – langsung masuk ke '{}'.",
                    player.getUsername(), serverName);
            player.createConnectionRequest(serverOpt.get()).fireAndForget();
            return;
        }

        // Masuk antrian
        LinkedBlockingDeque<UUID> queue = queues.computeIfAbsent(serverName, k -> new LinkedBlockingDeque<>());
        queue.addLast(player.getUniqueId());
        inQueue.add(player.getUniqueId());

        int position = queue.size();
        int total    = position;
        String msg = config.formatQueue(config.getMsgQueueEnter(),
                config.getDisplayName(serverName), position, total);
        player.sendMessage(Component.text(msg));

        logger.info("[Areconet] '{}' masuk antrian '{}' posisi #{}.",
                player.getUsername(), serverName, position);

        // Mulai scheduler jika belum berjalan
        queueTasks.computeIfAbsent(serverName, k -> startQueueScheduler(serverName));
    }

    /**
     * Hapus player dari semua queue (saat disconnect).
     */
    public void removeFromQueue(UUID playerId) {
        if (!inQueue.remove(playerId)) return;
        queues.values().forEach(q -> q.remove(playerId));
        logger.debug("[Areconet] Player {} dihapus dari queue.", playerId);
    }

    /**
     * Cek apakah player sedang dalam queue.
     */
    public boolean isInQueue(UUID playerId) {
        return inQueue.contains(playerId);
    }

    /**
     * Ambil posisi player dalam queue server tertentu (1-based).
     * Return -1 jika tidak ada.
     */
    public int getPosition(UUID playerId, String serverName) {
        LinkedBlockingDeque<UUID> queue = queues.get(serverName);
        if (queue == null) return -1;
        int pos = 1;
        for (UUID id : queue) {
            if (id.equals(playerId)) return pos;
            pos++;
        }
        return -1;
    }

    public int getQueueSize(String serverName) {
        LinkedBlockingDeque<UUID> queue = queues.get(serverName);
        return queue == null ? 0 : queue.size();
    }

    /**
     * Hentikan semua queue scheduler dan bersihkan state.
     */
    public void shutdown() {
        queueTasks.values().forEach(ScheduledTask::cancel);
        queueTasks.clear();
        queues.clear();
        inQueue.clear();
        logger.info("[Areconet] QueueManager dimatikan.");
    }

    // Scheduler

    private ScheduledTask startQueueScheduler(String serverName) {
        long interval = config.getQueueIntervalSeconds();
        logger.info("[Areconet] Queue scheduler dimulai untuk '{}' setiap {} detik.",
                serverName, interval);

        return proxy.getScheduler()
                .buildTask(pluginInstance, () -> processNext(serverName))
                .repeat(interval, TimeUnit.SECONDS)
                .schedule();
    }

    /**
     * Proses player berikutnya dalam antrian.
     * Jika antrian kosong, hentikan scheduler.
     */
    private void processNext(String serverName) {
        LinkedBlockingDeque<UUID> queue = queues.get(serverName);

        if (queue == null || queue.isEmpty()) {
            // Antrian kosong – hentikan scheduler
            ScheduledTask task = queueTasks.remove(serverName);
            if (task != null) {
                task.cancel();
                logger.info("[Areconet] Antrian '{}' kosong, scheduler dihentikan.", serverName);
            }
            return;
        }

        UUID nextId = queue.pollFirst();
        if (nextId == null) return;

        inQueue.remove(nextId);

        Optional<Player> playerOpt = proxy.getPlayer(nextId);
        if (playerOpt.isEmpty()) {
            logger.debug("[Areconet] Player {} sudah offline, skip dari queue.", nextId);
            // Langsung proses yang berikutnya tanpa nunggu interval
            processNext(serverName);
            return;
        }

        Optional<RegisteredServer> serverOpt = proxy.getServer(serverName);
        if (serverOpt.isEmpty()) {
            logger.error("[Areconet] Server '{}' tidak ditemukan saat proses queue!", serverName);
            return;
        }

        Player player = playerOpt.get();
        String displayName = config.getDisplayName(serverName);

        String msg = config.formatQueue(config.getMsgQueueConnecting(), displayName, 0, 0);
        player.sendMessage(Component.text(msg));
        player.createConnectionRequest(serverOpt.get()).fireAndForget();

        logger.info("[Areconet] '{}' dikirim ke '{}' dari queue.", player.getUsername(), serverName);

        // Update posisi untuk player yang tersisa
        broadcastQueuePositions(serverName);
    }

    /**
     * Kirim update posisi ke semua player dalam antrian.
     */
    private void broadcastQueuePositions(String serverName) {
        LinkedBlockingDeque<UUID> queue = queues.get(serverName);
        if (queue == null || queue.isEmpty()) return;

        int total = queue.size();
        int pos   = 1;
        for (UUID id : queue) {
            Optional<Player> p = proxy.getPlayer(id);
            if (p.isPresent()) {
                String msg = config.formatQueue(config.getMsgQueuePosition(),
                        config.getDisplayName(serverName), pos, total);
                p.get().sendMessage(Component.text(msg));
            }
            pos++;
        }
    }
}