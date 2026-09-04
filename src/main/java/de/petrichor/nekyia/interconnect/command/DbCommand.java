package de.petrichor.nekyia.interconnect.command;

import de.petrichor.nekyia.interconnect.config.DatabaseConfig;
import de.petrichor.nekyia.interconnect.database.AdminerEditorServer;
import de.petrichor.nekyia.interconnect.database.LocalMariaDbService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DbCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final DatabaseConfig databaseConfig;
    private final LocalMariaDbService databaseService;
    private final AdminerEditorServer adminerEditorServer;

    public DbCommand(JavaPlugin plugin, DatabaseConfig databaseConfig, LocalMariaDbService databaseService, AdminerEditorServer adminerEditorServer) {
        this.plugin = plugin;
        this.databaseConfig = databaseConfig;
        this.databaseService = databaseService;
        this.adminerEditorServer = adminerEditorServer;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && "list".equalsIgnoreCase(args[0])) {
            sender.sendMessage("Selected plugin version: " + databaseConfig.selectedPluginVersion() + " (" + databaseConfig.pluginVersionFolderName() + ")");
            sender.sendMessage("Configured databases: " + String.join(", ", databaseConfig.allDatabaseNames()));
            return true;
        }

        if (args.length == 1 && "adminer".equalsIgnoreCase(args[0])) {
            sender.sendMessage("Adminer Editor: " + adminerEditorServer.url());
            return true;
        }

        if (args.length == 2 && "add".equalsIgnoreCase(args[0])) {
            addDatabase(sender, args[1]);
            return true;
        }

        if (args.length != 2 || !"reset".equalsIgnoreCase(args[0])) {
            sender.sendMessage("Usage: /db <adminer|list|add <database>|reset <plugin>>");
            return true;
        }

        String requestedPlugin = args[1];
        String targetPlugin = databaseConfig.databaseNameForPlugin(requestedPlugin);
        if (plugin.getServer().getPluginManager().getPlugin(requestedPlugin) == null && !matchesConfiguredPlugin(requestedPlugin)) {
            sender.sendMessage("Unknown plugin: " + targetPlugin);
            return true;
        }

        sender.sendMessage("Resetting local MariaDB database `" + targetPlugin + "` for " + requestedPlugin + "...");

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                LocalMariaDbService.ResetResult result = databaseService.resetDatabase(targetPlugin);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sender.sendMessage("Reset database `" + result.databaseName() + "`.");
                    sender.sendMessage("JDBC URL: " + result.jdbcUrl());
                });
            } catch (Exception exception) {
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage("Database reset failed: " + exception.getMessage()));
            }
        });

        return true;
    }

    private void addDatabase(CommandSender sender, String databaseName) {
        sender.sendMessage("Creating local MariaDB database " + databaseName + "...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                LocalMariaDbService.ResetResult result = databaseService.createDatabase(databaseName);
                databaseConfig.addDatabase(result.databaseName());
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sender.sendMessage("Added database `" + result.databaseName() + "` to databases.yaml.");
                    sender.sendMessage("JDBC URL: " + result.jdbcUrl());
                });
            } catch (Exception exception) {
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage("Database creation failed: " + exception.getMessage()));
            }
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return List.of("adminer", "list", "add", "reset").stream()
                .filter(option -> option.startsWith(partial))
                .toList();
        }

        if (args.length == 2 && "reset".equalsIgnoreCase(args[0])) {
            String partialPluginName = args[1].toLowerCase();
            Set<String> suggestions = new LinkedHashSet<>();
            Arrays.stream(plugin.getServer().getPluginManager().getPlugins())
                .map(Plugin::getName)
                .filter(pluginName -> pluginName.toLowerCase().startsWith(partialPluginName))
                .forEach(suggestions::add);
            databaseConfig.pluginMappings().keySet().stream()
                .filter(pluginName -> pluginName.toLowerCase().startsWith(partialPluginName))
                .forEach(suggestions::add);
            pluginVersionJars().stream()
                .filter(pluginName -> pluginName.toLowerCase().startsWith(partialPluginName))
                .forEach(suggestions::add);
            return List.copyOf(suggestions);
        }

        if (args.length == 2 && "add".equalsIgnoreCase(args[0])) {
            return databaseConfig.databases();
        }

        return List.of();
    }

    private List<String> pluginVersionJars() {
        Path targetPluginsFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize().getParent();
        if (targetPluginsFolder == null || targetPluginsFolder.getParent() == null) {
            return List.of();
        }

        Path serverRoot = targetPluginsFolder.getParent();
        Path pluginFolder = serverRoot.resolve(databaseConfig.pluginVersionFolderName());
        if (!Files.isDirectory(pluginFolder) && serverRoot.getParent() != null) {
            pluginFolder = serverRoot.getParent().resolve(databaseConfig.pluginVersionFolderName());
        }
        if (!Files.isDirectory(pluginFolder)) {
            return List.of();
        }

        try {
            return Files.list(pluginFolder)
                .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                .map(path -> path.getFileName().toString())
                .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private boolean matchesConfiguredPlugin(String pluginName) {
        String normalizedPluginName = pluginName.trim();
        if (normalizedPluginName.toLowerCase().endsWith(".jar")) {
            normalizedPluginName = normalizedPluginName.substring(0, normalizedPluginName.length() - 4);
        }

        String lowerPluginName = normalizedPluginName.toLowerCase();
        return databaseConfig.pluginMappings().keySet().stream()
            .anyMatch(configuredPlugin -> lowerPluginName.startsWith(configuredPlugin.toLowerCase()));
    }
}
