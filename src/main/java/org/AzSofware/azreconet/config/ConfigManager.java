package org.AzSofware.azreconet.config;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ConfigManager {

    private final Path   dataDirectory;
    private final Logger logger;

    private String              hubServer             = "hub";
    private int                 reconnectDelaySeconds = 3;
    private int                 pingIntervalSeconds   = 5;

    private final Map<String, String> monitoredServers = new LinkedHashMap<>();

    public ConfigManager(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger        = logger;
    }


    public void load() {
        Path configFile = dataDirectory.resolve("config.yml");

        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            logger.error("[Areconet] Gagal membuat direktori data: {}", e.getMessage());
            return;
        }

        if (!Files.exists(configFile)) {
            saveDefaultConfig(configFile);
        }

        try (InputStream in = Files.newInputStream(configFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(in);

            if (data == null) {
                logger.warn("[Areconet] config.yml kosong – menggunakan nilai default.");
                return;
            }

            hubServer             = getString(data, "hub-server",              hubServer);
            reconnectDelaySeconds = getInt(data,    "reconnect-delay-seconds", reconnectDelaySeconds);
            pingIntervalSeconds   = getInt(data,    "ping-interval-seconds",   pingIntervalSeconds);

            monitoredServers.clear();
            Object rawList = data.get("monitored-servers");
            if (rawList instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> entry) {
                        Object nameObj        = entry.get("name");
                        Object displayNameObj = entry.get("display-name");
                        String name        = nameObj != null ? String.valueOf(nameObj) : "";
                        String displayName = displayNameObj != null ? String.valueOf(displayNameObj) : name;
                        if (!name.isBlank()) {
                            monitoredServers.put(name, displayName);
                        }
                    }
                }
            }

            logger.info("[Areconet] Konfigurasi dimuat:");
            logger.info("[Areconet]   hub-server              = {}", hubServer);
            logger.info("[Areconet]   reconnect-delay-seconds = {}", reconnectDelaySeconds);
            logger.info("[Areconet]   ping-interval-seconds   = {}", pingIntervalSeconds);
            logger.info("[Areconet]   monitored-servers       = {}", monitoredServers.keySet());

        } catch (IOException e) {
            logger.error("[Areconet] Gagal membaca config.yml: {}", e.getMessage());
        }
    }


    public String              getHubServer()             { return hubServer; }
    public int                 getReconnectDelaySeconds() { return reconnectDelaySeconds; }
    public int                 getPingIntervalSeconds()   { return pingIntervalSeconds; }

    public Map<String, String> getMonitoredServers()      { return Collections.unmodifiableMap(monitoredServers); }

    public boolean isMonitored(String serverName) {
        return monitoredServers.containsKey(serverName);
    }

    public String getDisplayName(String serverName) {
        return monitoredServers.getOrDefault(serverName, serverName);
    }

    private void saveDefaultConfig(Path dest) {
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            if (in == null) { logger.error("[Areconet] config.yml tidak ada di dalam jar!"); return; }
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            logger.info("[Areconet] config.yml default dibuat di: {}", dest);
        } catch (IOException e) {
            logger.error("[Areconet] Gagal menyalin config.yml default: {}", e.getMessage());
        }
    }

    private String getString(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key); return (v instanceof String s) ? s : fallback;
    }

    private int getInt(Map<String, Object> m, String key, int fallback) {
        Object v = m.get(key); return (v instanceof Number n) ? n.intValue() : fallback;
    }
}
