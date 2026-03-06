package org.AzSofware.azreconet.command;

import org.AzSofware.azreconet.config.ConfigManager;
import org.AzSofware.azreconet.manager.QueueManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * QueueCommand
 *
 * Command: /queue <server>
 * - Tab completion menampilkan daftar server dari monitored-servers config
 * - Player priority langsung masuk tanpa antri
 * - Cooldown mencegah spam
 */
public class QueueCommand implements SimpleCommand {

    private final ConfigManager config;
    private final QueueManager  queueManager;
    private final ProxyServer   proxy;

    public QueueCommand(ConfigManager config, QueueManager queueManager, ProxyServer proxy) {
        this.config       = config;
        this.queueManager = queueManager;
        this.proxy        = proxy;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[]      args   = invocation.arguments();

        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("Command ini hanya untuk player!"));
            return;
        }

        if (args.length == 0) {
            String msg = config.format("&eGunakan: &f/queue <server>");
            player.sendMessage(Component.text(msg));

            // Tampilkan daftar server yang tersedia
            String serverList = String.join("&7, &b", config.getMonitoredServers().keySet());
            String listMsg = config.format("&7Server tersedia: &b" + serverList);
            player.sendMessage(Component.text(listMsg));
            return;
        }

        String serverName = args[0].toLowerCase();

        // Cek apakah server ada di monitored-servers
        if (!config.isMonitored(serverName)) {
            String msg = config.formatServer(config.getMsgQueueNotMonitored(), serverName);
            player.sendMessage(Component.text(msg));
            return;
        }

        queueManager.addToQueue(player, serverName, false);
    }

    /**
     * Tab completion:
     * - Ketik /queue → tampilkan semua monitored-servers
     * - Ketik /queue sur → filter yang cocok (survival, dsb)
     */
    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();

        // Hanya berikan suggestion untuk argumen pertama
        if (args.length != 1) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        String input = args[0].toLowerCase();

        List<String> suggestions = config.getMonitoredServers().keySet().stream()
                .filter(name -> name.toLowerCase().startsWith(input))
                .sorted()
                .collect(Collectors.toList());

        return CompletableFuture.completedFuture(suggestions);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source() instanceof Player;
    }
}