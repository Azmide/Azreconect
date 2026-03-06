package org.AzSofware.azreconet.config;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * ConfigManager
 * Membaca config.yml termasuk pesan, prefix, dan pengaturan queue.
 */
public class ConfigManager {

    private final Path   dataDirectory;
    private final Logger logger;

    // General
    private String hubServer             = "hub";
    private int    reconnectDelaySeconds = 3;
    private int    pingIntervalSeconds   = 5;

    // Queue
    private boolean queueEnabled          = true;
    private int     queueIntervalSeconds  = 3;
    private String  priorityPermission    = "areconet.priority";
    private boolean prioritySkipQueue     = true;

    // Messages
    private String msgPrefix         = "&8[&bAreconet&8] &r";
    private String msgServerOffline  = "&eServer {server} sedang offline. Kamu dipindahkan ke hub...";
    private String msgServerOnline   = "&aServer {server} sudah online! Menghubungkan kembali...";
    private String msgQueueEnter     = "&7Kamu masuk antrian ke server &b{server}&7. Posisi: &f#{position}";
    private String msgQueuePosition  = "&7Posisi antrian kamu: &f#{position} &7dari &f{total}";
    private String msgQueueConnecting= "&aGiliran kamu! Menghubungkan ke &b{server}&a...";
    private String msgQueueBypass    = "&aKamu memiliki prioritas! Langsung masuk ke &b{server}&a...";
    private String msgBypassReconnect= "&7Kamu memiliki bypass, tidak akan di-reconnect otomatis.";

    // Monitored servers
    private final Map<String, String> monitoredServers = new LinkedHashMap<>();

    public ConfigManager(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger        = logger;
    }

    // Load

    @SuppressWarnings("unchecked")
    public void load() {
        Path configFile = dataDirectory.resolve("config.yml");

        try { Files.createDirectories(dataDirectory); }
        catch (IOException e) { logger.error("[Areconet] Gagal membuat direktori: {}", e.getMessage()); return; }

        if (!Files.exists(configFile)) saveDefaultConfig(configFile);

        try (InputStream in = Files.newInputStream(configFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(in);
            if (data == null) { logger.warn("[Areconet] config.yml kosong."); return; }

            hubServer             = getString(data, "hub-server", hubServer);
            reconnectDelaySeconds = getInt(data, "reconnect-delay-seconds", reconnectDelaySeconds);
            pingIntervalSeconds   = getInt(data, "ping-interval-seconds", pingIntervalSeconds);

            // Queue settings
            if (data.get("queue") instanceof Map<?, ?> q) {
                queueEnabled         = getBool((Map<String,Object>)(Map<?,?>)q, "enabled", queueEnabled);
                queueIntervalSeconds = getInt((Map<String,Object>)(Map<?,?>)q, "interval-seconds", queueIntervalSeconds);
                priorityPermission   = getString((Map<String,Object>)(Map<?,?>)q, "priority-permission", priorityPermission);
                prioritySkipQueue    = getBool((Map<String,Object>)(Map<?,?>)q, "priority-skip-queue", prioritySkipQueue);
            }

            // Messages
            if (data.get("messages") instanceof Map<?, ?> m) {
                Map<String,Object> msgs = (Map<String,Object>)(Map<?,?>)m;
                msgPrefix          = getString(msgs, "prefix",           msgPrefix);
                msgServerOffline   = getString(msgs, "server-offline",   msgServerOffline);
                msgServerOnline    = getString(msgs, "server-online",    msgServerOnline);
                msgQueueEnter      = getString(msgs, "queue-enter",      msgQueueEnter);
                msgQueuePosition   = getString(msgs, "queue-position",   msgQueuePosition);
                msgQueueConnecting = getString(msgs, "queue-connecting",  msgQueueConnecting);
                msgQueueBypass     = getString(msgs, "queue-bypass",     msgQueueBypass);
                msgBypassReconnect = getString(msgs, "bypass-reconnect", msgBypassReconnect);
            }

            // Monitored servers
            monitoredServers.clear();
            if (data.get("monitored-servers") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> entry) {
                        Object nameObj        = entry.get("name");
                        Object displayNameObj = entry.get("display-name");
                        String name        = nameObj != null ? String.valueOf(nameObj) : "";
                        String displayName = displayNameObj != null ? String.valueOf(displayNameObj) : name;
                        if (!name.isBlank()) monitoredServers.put(name, displayName);
                    }
                }
            }

            logger.info("[Areconet] Config dimuat. Servers: {}, Queue: {}",
                    monitoredServers.keySet(), queueEnabled ? "ON" : "OFF");

        } catch (IOException e) {
            logger.error("[Areconet] Gagal membaca config.yml: {}", e.getMessage());
        }
    }

    // Format message helpers

    /** Terapkan prefix + color codes ke pesan. */
    public String format(String message) {
        return colorize(msgPrefix + message);
    }

    /** Format pesan dengan placeholder {server}. */
    public String formatServer(String message, String server) {
        return colorize((msgPrefix + message).replace("{server}", server));
    }

    /** Format pesan dengan placeholder {server}, {position}, {total}. */
    public String formatQueue(String message, String server, int position, int total) {
        return colorize((msgPrefix + message)
                .replace("{server}", server)
                .replace("{position}", String.valueOf(position))
                .replace("{total}", String.valueOf(total)));
    }

    /** Konversi &-color codes ke §. */
    public static String colorize(String text) {
        return text.replace("&0", "§0").replace("&1", "§1").replace("&2", "§2")
                .replace("&3", "§3").replace("&4", "§4").replace("&5", "§5")
                .replace("&6", "§6").replace("&7", "§7").replace("&8", "§8")
                .replace("&9", "§9").replace("&a", "§a").replace("&b", "§b")
                .replace("&c", "§c").replace("&d", "§d").replace("&e", "§e")
                .replace("&f", "§f").replace("&l", "§l").replace("&m", "§m")
                .replace("&n", "§n").replace("&o", "§o").replace("&k", "§k")
                .replace("&r", "§r");
    }

    // Getters

    public String  getHubServer()             { return hubServer; }
    public int     getReconnectDelaySeconds() { return reconnectDelaySeconds; }
    public int     getPingIntervalSeconds()   { return pingIntervalSeconds; }
    public boolean isQueueEnabled()           { return queueEnabled; }
    public int     getQueueIntervalSeconds()  { return queueIntervalSeconds; }
    public String  getPriorityPermission()    { return priorityPermission; }
    public boolean isPrioritySkipQueue()      { return prioritySkipQueue; }

    public String getMsgServerOffline()   { return msgServerOffline; }
    public String getMsgServerOnline()    { return msgServerOnline; }
    public String getMsgQueueEnter()      { return msgQueueEnter; }
    public String getMsgQueuePosition()   { return msgQueuePosition; }
    public String getMsgQueueConnecting() { return msgQueueConnecting; }
    public String getMsgQueueBypass()     { return msgQueueBypass; }
    public String getMsgBypassReconnect() { return msgBypassReconnect; }

    public Map<String, String> getMonitoredServers() { return Collections.unmodifiableMap(monitoredServers); }
    public boolean isMonitored(String name)          { return monitoredServers.containsKey(name); }
    public String  getDisplayName(String name)       { return monitoredServers.getOrDefault(name, name); }

    // Private helpers

    private void saveDefaultConfig(Path dest) {
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            if (in == null) { logger.error("[Areconet] config.yml tidak ada di jar!"); return; }
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            logger.info("[Areconet] config.yml default dibuat di: {}", dest);
        } catch (IOException e) { logger.error("[Areconet] Gagal salin config.yml: {}", e.getMessage()); }
    }

    private String  getString(Map<String,Object> m, String k, String def) { Object v=m.get(k); return v instanceof String s ? s : def; }
    private int     getInt   (Map<String,Object> m, String k, int def)    { Object v=m.get(k); return v instanceof Number n ? n.intValue() : def; }
    private boolean getBool  (Map<String,Object> m, String k, boolean def){ Object v=m.get(k); return v instanceof Boolean b ? b : def; }
}
