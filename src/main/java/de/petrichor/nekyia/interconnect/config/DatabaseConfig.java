package de.petrichor.nekyia.interconnect.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DatabaseConfig {
    private final Plugin plugin;
    private final Path configPath;
    private YamlConfiguration config;

    public DatabaseConfig(Plugin plugin) {
        this.plugin = plugin;
        this.configPath = plugin.getDataFolder().toPath().resolve("databases.yaml");
    }

    public void load() {
        try {
            Files.createDirectories(configPath.getParent());
            if (Files.notExists(configPath)) {
                Files.writeString(configPath, """
                    adminer-editor:
                      enabled: true
                      host: 127.0.0.1
                      port: 8090

                    local-mariadb:
                      port: 13306

                    plugin-versions:
                      selected: "26.2"
                      folder-pattern: "plugins-%version%"
                      plugins:
                        - BetonQuest
                        - ChatControl
                        - Citizens
                        - FastAsyncWorldEdit
                        - InteractiveChat
                        - InteractiveChat-PacketEvents
                        - LuckPerms
                        - Nations
                        - Nexo
                        - PacketEvents
                        - PlaceholderAPI
                        - Pl3xMap
                        - PlayerActionAdapter
                        - Proficisci
                        - TerranovaLib
                        - Vault
                        - WorldGuard

                    bootstrap:
                      commands:
                        - 'lp group default permission set chatcontrol.channel.standard true'
                        - 'lp group default permission set chatcontrol.channel.join.standard.write true'
                        - 'lp group default permission set chatcontrol.channel.autojoin.standard.write true'
                        - 'lp group default meta setprefix 100 "&7[Player] "'

                    databases:
                      - network
                      - nations

                    accounts:
                      - username: minecraft
                        password: minecraft
                        root: true
                    """);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create databases.yaml", exception);
        }

        config = YamlConfiguration.loadConfiguration(configPath.toFile());
        addMissingDefaults();
    }

    public boolean adminerEditorEnabled() {
        return config.getBoolean("adminer-editor.enabled", true);
    }

    public String adminerEditorHost() {
        return config.getString("adminer-editor.host", "127.0.0.1");
    }

    public int adminerEditorPort() {
        return config.getInt("adminer-editor.port", 8090);
    }

    public int localMariaDbPort() {
        return config.getInt("local-mariadb.port", 13306);
    }

    public String selectedPluginVersion() {
        return config.getString("plugin-versions.selected", "26.2");
    }

    public String pluginVersionFolderName() {
        String folderPattern = config.getString("plugin-versions.folder-pattern", "plugins-%version%");
        return folderPattern.replace("%version%", selectedPluginVersion());
    }

    public List<String> databases() {
        return List.copyOf(config.getStringList("databases"));
    }

    public List<String> allDatabaseNames() {
        Set<String> databaseNames = new LinkedHashSet<>(databases());
        databaseNames.addAll(pluginMappings().values());
        return List.copyOf(databaseNames);
    }

    public Map<String, String> pluginMappings() {
        Map<String, String> mappings = new LinkedHashMap<>();
        if (config.isList("plugin-versions.plugins")) {
            for (String pluginName : config.getStringList("plugin-versions.plugins")) {
                if (!pluginName.isBlank()) {
                    mappings.put(pluginName, pluginName);
                }
            }
        } else if (config.isConfigurationSection("plugin-versions.plugins")) {
            for (String pluginPrefix : config.getConfigurationSection("plugin-versions.plugins").getKeys(false)) {
                String databaseName = config.getString("plugin-versions.plugins." + pluginPrefix, "");
                if (!pluginPrefix.isBlank() && databaseName != null && !databaseName.isBlank()) {
                    mappings.put(pluginPrefix, databaseName);
                }
            }
        }
        return Map.copyOf(mappings);
    }

    public String databaseNameForPlugin(String pluginName) {
        String normalizedPluginName = pluginName.trim();
        if (normalizedPluginName.toLowerCase().endsWith(".jar")) {
            normalizedPluginName = normalizedPluginName.substring(0, normalizedPluginName.length() - 4);
        }

        String lowerPluginName = normalizedPluginName.toLowerCase();
        return pluginMappings().entrySet().stream()
            .sorted((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()))
            .filter(entry -> lowerPluginName.startsWith(entry.getKey().toLowerCase()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(normalizedPluginName);
    }

    public List<DatabaseAccount> accounts() {
        List<DatabaseAccount> accounts = new ArrayList<>();
        for (Map<?, ?> accountConfig : config.getMapList("accounts")) {
            Object usernameValue = accountConfig.get("username");
            Object passwordValue = accountConfig.get("password");
            Object rootValue = accountConfig.get("root");
            String username = String.valueOf(usernameValue == null ? "" : usernameValue).trim();
            String password = String.valueOf(passwordValue == null ? "" : passwordValue);
            boolean rootPrivileges = Boolean.parseBoolean(String.valueOf(rootValue == null ? false : rootValue));
            if (!username.isBlank()) {
                accounts.add(new DatabaseAccount(username, password, rootPrivileges));
            }
        }
        return List.copyOf(accounts);
    }

    public List<String> bootstrapCommands() {
        return List.copyOf(config.getStringList("bootstrap.commands"));
    }

    public boolean addDatabase(String databaseName) {
        List<String> databases = new ArrayList<>(databases());
        if (databases.stream().anyMatch(existing -> existing.equalsIgnoreCase(databaseName))) {
            return false;
        }

        databases.add(databaseName);
        config.set("databases", databases);
        save();
        return true;
    }

    private void save() {
        try {
            config.save(configPath.toFile());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save databases.yaml", exception);
        }
    }

    private void addMissingDefaults() {
        boolean changed = false;

        if (!config.isList("databases")) {
            config.set("databases", List.of("network", "nations"));
            changed = true;
        }

        if (!config.isInt("local-mariadb.port") || config.getInt("local-mariadb.port") == 3306) {
            config.set("local-mariadb.port", 13306);
            changed = true;
        }

        if (!config.isConfigurationSection("plugin-versions")) {
            Map<String, Object> pluginVersions = new LinkedHashMap<>();
            pluginVersions.put("selected", "26.2");
            pluginVersions.put("folder-pattern", "plugins-%version%");
            pluginVersions.put("plugins", defaultPluginNames());
            config.set("plugin-versions", pluginVersions);
            changed = true;
        } else {
            if (!config.isString("plugin-versions.selected")) {
                config.set("plugin-versions.selected", "26.2");
                changed = true;
            }
            if (!config.isString("plugin-versions.folder-pattern")) {
                config.set("plugin-versions.folder-pattern", "plugins-%version%");
                changed = true;
            }
            if (!config.isList("plugin-versions.plugins") && !config.isConfigurationSection("plugin-versions.plugins")) {
                config.set("plugin-versions.plugins", defaultPluginNames());
                changed = true;
            } else if (config.isList("plugin-versions.plugins")) {
                List<String> pluginNames = new ArrayList<>(config.getStringList("plugin-versions.plugins"));
                for (String defaultPluginName : defaultPluginNames()) {
                    if (pluginNames.stream().noneMatch(existing -> existing.equalsIgnoreCase(defaultPluginName))) {
                        pluginNames.add(defaultPluginName);
                        changed = true;
                    }
                }
                if (changed) {
                    config.set("plugin-versions.plugins", pluginNames);
                }
            } else {
                List<String> pluginNames = new ArrayList<>(config.getConfigurationSection("plugin-versions.plugins").getKeys(false));
                for (String defaultPluginName : defaultPluginNames()) {
                    if (pluginNames.stream().noneMatch(existing -> existing.equalsIgnoreCase(defaultPluginName))) {
                        pluginNames.add(defaultPluginName);
                    }
                }
                config.set("plugin-versions.plugins", pluginNames);
                changed = true;
            }
        }

        if (!config.isList("accounts")) {
            Map<String, Object> minecraftAccount = new LinkedHashMap<>();
            minecraftAccount.put("username", "minecraft");
            minecraftAccount.put("password", "minecraft");
            minecraftAccount.put("root", true);
            config.set("accounts", List.of(minecraftAccount));
            changed = true;
        }

        if (!config.isList("bootstrap.commands")) {
            config.set("bootstrap.commands", defaultBootstrapCommands());
            changed = true;
        }

        if (changed) {
            save();
        }
    }

    public record DatabaseAccount(String username, String password, boolean rootPrivileges) {
    }

    private static List<String> defaultPluginNames() {
        return List.of(
            "BetonQuest",
            "ChatControl",
            "Citizens",
            "FastAsyncWorldEdit",
            "InteractiveChat",
            "InteractiveChat-PacketEvents",
            "LuckPerms",
            "Nations",
            "Nexo",
            "PacketEvents",
            "PlaceholderAPI",
            "Pl3xMap",
            "PlayerActionAdapter",
            "Proficisci",
            "TerranovaLib",
            "Vault",
            "WorldGuard"
        );
    }

    private static List<String> defaultBootstrapCommands() {
        return List.of(
            "lp group default permission set chatcontrol.channel.standard true",
            "lp group default permission set chatcontrol.channel.join.standard.write true",
            "lp group default permission set chatcontrol.channel.autojoin.standard.write true",
            "lp group default meta setprefix 100 \"&7[Player] \""
        );
    }
}
