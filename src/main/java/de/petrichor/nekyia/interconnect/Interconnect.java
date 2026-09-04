package de.petrichor.nekyia.interconnect;

import de.petrichor.nekyia.interconnect.command.DbCommand;
import de.petrichor.nekyia.interconnect.config.DatabaseConfig;
import de.petrichor.nekyia.interconnect.database.AdminerEditorServer;
import de.petrichor.nekyia.interconnect.database.LocalMariaDbService;
import de.petrichor.nekyia.interconnect.plugin.PluginVersionSyncService;
import org.bukkit.plugin.java.JavaPlugin;

public final class Interconnect extends JavaPlugin {
    private DatabaseConfig databaseConfig;
    private LocalMariaDbService databaseService;
    private AdminerEditorServer adminerEditorServer;

    @Override
    public void onEnable() {
        databaseConfig = new DatabaseConfig(this);
        databaseConfig.load();

        PluginVersionSyncService.SyncResult syncResult = new PluginVersionSyncService(this, databaseConfig).syncSelectedVersion();
        if (syncResult.copiedFiles() > 0) {
            getLogger().info("Copied " + syncResult.copiedFiles() + " file(s) from " + databaseConfig.pluginVersionFolderName() + ".");
            if (syncResult.copiedJars() > 0) {
                getLogger().warning("Copied " + syncResult.copiedJars() + " plugin jar(s), but Paper has already scanned plugins for this boot.");
                getLogger().warning("Stopping the server now. Start it again to load the synchronized plugin set.");
                getServer().getScheduler().runTask(this, () -> getServer().shutdown());
                return;
            }
        }
        for (String error : syncResult.errors()) {
            getLogger().warning(error);
        }

        databaseService = new LocalMariaDbService(this, databaseConfig);
        try {
            databaseService.start();
            databaseService.ensureDatabases(databaseConfig.allDatabaseNames());
            databaseService.ensureAccounts(databaseConfig.accounts());
        } catch (Exception exception) {
            getLogger().log(java.util.logging.Level.SEVERE, "Could not start the local MariaDB test server.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        adminerEditorServer = new AdminerEditorServer(this, databaseConfig, databaseService);
        try {
            adminerEditorServer.start();
        } catch (Exception exception) {
            getLogger().log(java.util.logging.Level.SEVERE, "Could not start Adminer Editor.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        DbCommand dbCommand = new DbCommand(this, databaseConfig, databaseService, adminerEditorServer);
        getCommand("db").setExecutor(dbCommand);
        getCommand("db").setTabCompleter(dbCommand);
    }

    @Override
    public void onDisable() {
        if (adminerEditorServer != null) {
            adminerEditorServer.stop();
        }

        if (databaseService != null) {
            databaseService.stop();
        }
    }
}
