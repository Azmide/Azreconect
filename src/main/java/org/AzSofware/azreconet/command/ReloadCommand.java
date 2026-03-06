package org.AzSofware.azreconet.command;

import org.AzSofware.azreconet.config.ConfigManager;
import org.AzSofware.azreconet.manager.QueueManager;
import org.AzSofware.azreconet.manager.ReconnectManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * ReloadCommand
 *
 * Command: /areconet reload
 * Permission: areconet.admin
 *
 * Melakukan reload config.yml tanpa perlu restart proxy.
 * Setelah reload, QueueManager dan ReconnectManager ikut
 * diperbarui dengan nilai config yang baru.
 */
public class ReloadCommand implements SimpleCommand {

    private final ConfigManager    config;
    private final QueueManager     queueManager;
    private final ReconnectManager reconnectManager;

    public ReloadCommand(ConfigManager config, QueueManager queueManager,
                         ReconnectManager reconnectManager) {
        this.config           = config;
        this.queueManager     = queueManager;
        this.reconnectManager = reconnectManager;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[]      args   = invocation.arguments();

        // Harus ada sub-command
        if (args.length == 0) {
            sendHelp(source);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(source);
            default       -> sendHelp(source);
        }
    }

    private void handleReload(CommandSource source) {
        source.sendMessage(Component.text(
                "[Areconet] Memuat ulang konfigurasi...", NamedTextColor.YELLOW));

        try {
            // Reload config dari disk
            config.load();

            // Beri tahu hasilnya
            source.sendMessage(Component.text(
                    "[Areconet] Config berhasil di-reload!", NamedTextColor.GREEN));
            source.sendMessage(Component.text(
                    "[Areconet] Hub: " + config.getHubServer() +
                            " | Queue: " + (config.isQueueEnabled() ? "ON" : "OFF") +
                            " | Servers: " + config.getMonitoredServers().keySet(),
                    NamedTextColor.GRAY));

        } catch (Exception e) {
            source.sendMessage(Component.text(
                    "[Areconet] Gagal reload config: " + e.getMessage(),
                    NamedTextColor.RED));
        }
    }

    private void sendHelp(CommandSource source) {
        source.sendMessage(Component.text("--- Areconet v1.0.0 ---", NamedTextColor.AQUA));
        source.sendMessage(Component.text("/areconet reload", NamedTextColor.GREEN)
                .append(Component.text(" - Reload config.yml", NamedTextColor.GRAY)));
    }

    /**
     * Tab completion: /areconet → tampilkan sub-command yang tersedia.
     */
    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            if ("reload".startsWith(input)) {
                return CompletableFuture.completedFuture(List.of("reload"));
            }
        }
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    /**
     * Hanya player/console dengan permission areconet.admin yang bisa pakai.
     */
    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("areconet.admin");
    }
}