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
 * - Player priority langsung masuk tanpa antri (jika priority-skip-queue: true).
 * - Player biasa masuk ke LinkedBlockingDeque, diproses satu per satu
 *   sesuai interval yang dikonfigurasi.
 * - Cooldown mencegah player spam /queue.
 * - Satu queue & scheduler per server.
 */
public class QueueManager {

    private final ProxyServer   proxy;
    private final Logger        logger;
    private final ConfigManager config;
    private final Object        pluginInstance;

    /** Queue per server: nama server → deque UUID player. */
    private final ConcurrentHashMap<String, LinkedBlockingDeque<UUID>> queues     = new ConcurrentHashMap<>();

    /** Scheduled task per server. */
    private final ConcurrentHashMap<String, ScheduledTask>             queueTasks = new ConcurrentHashMap<>();

    /** UUID player → timestamp terakhir masuk queue (untuk cooldown). */
    private final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    /** Set UUID player yang sedang dalam queue (untuk cek duplikat). */
    private final Set<UUID> inQueue = ConcurrentHashMap.newKeySet();

    public QueueManager(ProxyServer proxy, Logger logger,
                        ConfigManager config, Object pluginInstance) {
        this.proxy          = proxy;
        this.logger         = logger;
        this.config         = config;
        this.pluginInstance = pluginInstance;
    }

    /**
     * Tambahkan player ke antrian server tertentu.
     * Dipanggil dari /queue command maupun ReconnectManager.
     *
     * @param player     Player yang akan dikirim
     * @param serverName Nama server tujuan
     */
    public void addToQueue(Player player, String serverName) {
        addToQueue(player, serverName, false);
    }

    /**
     * Versi internal dengan flag bypassCooldown.
     * bypassCooldown = true digunakan saat reconnect otomatis
     * agar tidak terkena cooldown.
     *
     * @param player          Player yang akan dikirim
     * @param serverName      Nama server tujuan
     * @param bypassCooldown  true = skip pengecekan cooldown
     */
    public void addToQueue(Player player, String serverName, boolean bypassCooldown) {

        Optional<RegisteredServer> serverOpt = proxy.getServer(serverName);
        if (serverOpt.isEmpty()) {
            String msg = config.formatServer(config.getMsgQueueNotFound(),
                    config.getDisplayName(serverName));
            player.sendMessage(Component.text(msg));
            logger.warn("[Areconet] Queue: server '{}' tidak ditemukan!", serverName);
            return;
        }

        boolean alreadyThere = player.getCurrentServer()
                .map(s -> s.getServerInfo().getName().equalsIgnoreCase(serverName))
                .orElse(false);
        if (alreadyThere) {
            String msg = config.formatServer(config.getMsgQueueAlreadyConn(),
                    config.getDisplayName(serverName));
            player.sendMessage(Component.text(msg));
            return;
        }

        if (inQueue.contains(player.getUniqueId())) {
            int pos = getPosition(player.getUniqueId(), serverName);
            String msg = config.formatQueue(config.getMsgQueueAlready(),
                    config.getDisplayName(serverName), pos, getQueueSize(serverName));
            player.sendMessage(Component.text(msg));
            return;
        }

        if (!bypassCooldown) {
            int remaining = getCooldownRemaining(player.getUniqueId());
            if (remaining > 0) {
                String msg = config.formatCooldown(config.getMsgQueueCooldown(), remaining);
                player.sendMessage(Component.text(msg));
                return;
            }
            // Set cooldown hanya untuk aksi manual (/queue command)
            setCooldown(player.getUniqueId());
        }

        // Priority skip queue
        if (config.isPrioritySkipQueue()
                && player.hasPermission(config.getPriorityPermission())) {
            String msg = config.formatServer(config.getMsgQueueBypass(),
                    config.getDisplayName(serverName));
            player.sendMessage(Component.text(msg));
            logger.info("[Areconet] '{}' priority – langsung masuk ke '{}'.",
                    player.getUsername(), serverName);
            player.createConnectionRequest(serverOpt.get()).fireAndForget();
            return;
        }

        // Masuk antrian normal
        LinkedBlockingDeque<UUID> queue =
                queues.computeIfAbsent(serverName, k -> new LinkedBlockingDeque<>());
        queue.addLast(player.getUniqueId());
        inQueue.add(player.getUniqueId());

        int position = queue.size();
        String msg = config.formatQueue(config.getMsgQueueEnter(),
                config.getDisplayName(serverName), position, position);
        player.sendMessage(Component.text(msg));

        logger.info("[Areconet] '{}' masuk antrian '{}' posisi #{}.",
                player.getUsername(), serverName, position);

        // Mulai scheduler jika belum berjalan
        queueTasks.computeIfAbsent(serverName, k -> startQueueScheduler(serverName));
    }

    /**
     * Hapus player dari semua queue.
     * Dipanggil saat player disconnect dari proxy.
     */
    public void removeFromQueue(UUID playerId) {
        if (!inQueue.remove(playerId)) return;
        queues.values().forEach(q -> q.remove(playerId));
        cooldowns.remove(playerId);
        logger.debug("[Areconet] Player {} dihapus dari queue.", playerId);
    }

    /** Cek apakah player sedang dalam queue. */
    public boolean isInQueue(UUID playerId) {
        return inQueue.contains(playerId);
    }

    /**
     * Ambil posisi player dalam queue server tertentu (1-based).
     * Return -1 jika tidak ada dalam antrian.
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

    /** Ambil jumlah player dalam antrian server tertentu. */
    public int getQueueSize(String serverName) {
        LinkedBlockingDeque<UUID> queue = queues.get(serverName);
        return queue == null ? 0 : queue.size();
    }

    /**
     * Cek sisa cooldown player dalam detik.
     * Return 0 jika sudah bisa queue lagi.
     */
    public int getCooldownRemaining(UUID playerId) {
        Long lastTime = cooldowns.get(playerId);
        if (lastTime == null) return 0;
        long elapsed  = (System.currentTimeMillis() - lastTime) / 1000L;
        int  remaining = (int) (config.getCooldownSeconds() - elapsed);
        return Math.max(0, remaining);
    }

    /** Hentikan semua scheduler dan bersihkan semua state. */
    public void shutdown() {
        queueTasks.values().forEach(ScheduledTask::cancel);
        queueTasks.clear();
        queues.clear();
        inQueue.clear();
        cooldowns.clear();
        logger.info("[Areconet] QueueManager dimatikan.");
    }

    // ── Scheduler ──────────────────────────────────────────────────────────

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

        // Player sudah offline? Skip dan langsung proses berikutnya
        Optional<Player> playerOpt = proxy.getPlayer(nextId);
        if (playerOpt.isEmpty()) {
            logger.debug("[Areconet] Player {} offline, skip dari queue.", nextId);
            processNext(serverName);
            return;
        }

        Optional<RegisteredServer> serverOpt = proxy.getServer(serverName);
        if (serverOpt.isEmpty()) {
            logger.error("[Areconet] Server '{}' tidak ditemukan saat proses queue!", serverName);
            return;
        }

        Player player      = playerOpt.get();
        String displayName = config.getDisplayName(serverName);

        // Kirim pesan "giliran kamu" lalu connect
        String msg = config.formatServer(config.getMsgQueueConnecting(), displayName);
        player.sendMessage(Component.text(msg));
        player.createConnectionRequest(serverOpt.get()).fireAndForget();

        logger.info("[Areconet] '{}' dikirim ke '{}' dari queue.",
                player.getUsername(), serverName);

        // Update posisi untuk player yang masih menunggu
        broadcastQueuePositions(serverName);
    }

    /**
     * Broadcast update posisi antrian ke semua player yang masih menunggu.
     */
    private void broadcastQueuePositions(String serverName) {
        LinkedBlockingDeque<UUID> queue = queues.get(serverName);
        if (queue == null || queue.isEmpty()) return;

        int total = queue.size();
        int pos   = 1;
        for (UUID id : queue) {
            int finalPos = pos;
            proxy.getPlayer(id).ifPresent(p -> {
                String msg = config.formatQueue(config.getMsgQueuePosition(),
                        config.getDisplayName(serverName), finalPos, total);
                p.sendMessage(Component.text(msg));
            });
            pos++;
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private void setCooldown(UUID playerId) {
        cooldowns.put(playerId, System.currentTimeMillis());
    }
}