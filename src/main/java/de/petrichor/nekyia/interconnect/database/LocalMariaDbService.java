package de.petrichor.nekyia.interconnect.database;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import de.petrichor.nekyia.interconnect.config.DatabaseConfig;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class LocalMariaDbService {
    private static final Pattern DATABASE_NAME = Pattern.compile("[a-z0-9_]{1,64}");
    private static final Pattern ACCOUNT_NAME = Pattern.compile("[A-Za-z0-9_]{1,32}");
    private static final List<String> ACCOUNT_HOSTS = List.of("localhost", "%");
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(15);

    private final Plugin plugin;
    private final DatabaseConfig config;
    private DB database;

    public LocalMariaDbService(Plugin plugin, DatabaseConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void start() throws Exception {
        Path dataFolder = plugin.getDataFolder().toPath();
        Path databaseFolder = dataFolder.resolve("mariadb");
        Path baseFolder = databaseFolder.resolve("base");
        Path dataDirectory = databaseFolder.resolve("data");

        Files.createDirectories(baseFolder);
        Files.createDirectories(dataDirectory);

        DBConfigurationBuilder databaseConfig = DBConfigurationBuilder.newBuilder();
        databaseConfig.setPort(config.localMariaDbPort());
        databaseConfig.setBaseDir(baseFolder.toFile());
        databaseConfig.setDataDir(dataDirectory.toFile());
        databaseConfig.setSecurityDisabled(false);

        boolean releasedDataDirectory = terminateLeftoverServers(baseFolder, dataDirectory);

        try {
            startDatabase(databaseConfig);
        } catch (Exception exception) {
            if (!isRecoverableDataDirectoryFailure(exception, dataDirectory)) {
                throw exception;
            }

            stop();
            if (releasedDataDirectory || terminateLeftoverServers(baseFolder, dataDirectory)) {
                plugin.getLogger().warning("Retrying the local MariaDB test server now that the data directory is no longer locked.");
                startDatabase(databaseConfig);
            } else {
                Path backupDirectory = databaseFolder.resolve("data-broken-" + BACKUP_TIMESTAMP.format(LocalDateTime.now()));
                plugin.getLogger().warning("Local MariaDB data directory looks broken. Moving it to " + backupDirectory.getFileName() + " and starting with a clean test database.");
                Files.move(dataDirectory, backupDirectory);
                Files.createDirectories(dataDirectory);
                startDatabase(databaseConfig);
            }
        }

        plugin.getLogger().info("Local MariaDB test server started on port " + database.getConfiguration().getPort() + ".");
    }

    private void startDatabase(DBConfigurationBuilder databaseConfig) throws Exception {
        ClassLoader previousClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(plugin.getClass().getClassLoader());
        try {
            Class.forName("org.mariadb.jdbc.Driver", true, plugin.getClass().getClassLoader());
            database = DB.newEmbeddedDB(databaseConfig.build());
            database.start();
        } finally {
            Thread.currentThread().setContextClassLoader(previousClassLoader);
        }
    }

    /**
     * A server that was killed instead of stopped leaves its mysqld process running. That process keeps
     * the data directory locked, so the next boot fails with "The data file './ibdata1' must be writable".
     */
    private boolean terminateLeftoverServers(Path baseFolder, Path dataDirectory) {
        List<ProcessHandle> leftovers = leftoverServers(baseFolder);
        if (leftovers.isEmpty()) {
            deletePidFiles(dataDirectory);
            return false;
        }

        plugin.getLogger().warning("Found " + leftovers.size() + " MariaDB process(es) left over from a previous server run. Shutting them down before starting.");
        boolean shutdownRequested = requestShutdown();
        for (ProcessHandle leftover : leftovers) {
            terminate(leftover, shutdownRequested);
        }
        deletePidFiles(dataDirectory);
        return true;
    }

    private List<ProcessHandle> leftoverServers(Path baseFolder) {
        Path binaryFolder = baseFolder.toAbsolutePath().normalize().resolve("bin");
        List<Path> executables = List.of(binaryFolder.resolve("mysqld.exe"), binaryFolder.resolve("mysqld"));
        return ProcessHandle.allProcesses()
            .filter(process -> process.info().command()
                .map(command -> executables.contains(Path.of(command).toAbsolutePath().normalize()))
                .orElse(false))
            .toList();
    }

    private boolean requestShutdown() {
        String rootUrl = DBConfigurationBuilder.newBuilder().setPort(config.localMariaDbPort()).build().getURL("");
        try {
            Class.forName("org.mariadb.jdbc.Driver", true, plugin.getClass().getClassLoader());
            try (Connection connection = DriverManager.getConnection(rootUrl, "root", "");
                 Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            }
            return true;
        } catch (Exception exception) {
            plugin.getLogger().fine("Could not ask the leftover MariaDB server for a clean shutdown: " + exception.getMessage());
            return false;
        }
    }

    private void terminate(ProcessHandle process, boolean shutdownRequested) {
        if (shutdownRequested && awaitExit(process)) {
            return;
        }

        process.destroy();
        if (!awaitExit(process)) {
            process.destroyForcibly();
            awaitExit(process);
        }
    }

    private boolean awaitExit(ProcessHandle process) {
        try {
            process.onExit().get(SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            return true;
        } catch (Exception exception) {
            return !process.isAlive();
        }
    }

    private void deletePidFiles(Path dataDirectory) {
        try (Stream<Path> files = Files.list(dataDirectory)) {
            for (Path file : files.filter(file -> file.getFileName().toString().endsWith(".pid")).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not delete stale MariaDB pid files: " + exception.getMessage());
        }
    }

    public void stop() {
        if (database == null) {
            return;
        }

        try {
            database.stop();
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not stop the local MariaDB test server: " + exception.getMessage());
        } finally {
            database = null;
        }
    }

    public ResetResult resetDatabase(String pluginName) throws Exception {
        ensureStarted();

        String databaseName = databaseName(pluginName);
        try (Connection connection = rootConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS `" + databaseName + "`");
            statement.executeUpdate("CREATE DATABASE `" + databaseName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }

        return new ResetResult(databaseName, jdbcUrl(databaseName));
    }

    public void ensureDatabases(Collection<String> databaseNames) throws Exception {
        ensureStarted();
        for (String databaseName : databaseNames) {
            createDatabase(databaseName(databaseName));
        }
    }

    public void ensureAccounts(Collection<DatabaseConfig.DatabaseAccount> accounts) throws Exception {
        ensureStarted();

        try (Connection connection = rootConnection();
             Statement statement = connection.createStatement()) {
            for (DatabaseConfig.DatabaseAccount account : accounts) {
                String username = accountName(account.username());
                String password = sqlString(account.password());
                for (String host : ACCOUNT_HOSTS) {
                    String accountSql = "'" + username + "'@'" + host + "'";
                    statement.executeUpdate("CREATE USER IF NOT EXISTS " + accountSql + " IDENTIFIED BY '" + password + "'");
                    statement.executeUpdate("ALTER USER " + accountSql + " IDENTIFIED BY '" + password + "'");
                    if (account.rootPrivileges()) {
                        statement.executeUpdate("GRANT ALL PRIVILEGES ON *.* TO " + accountSql + " WITH GRANT OPTION");
                    }
                }
            }
            statement.executeUpdate("FLUSH PRIVILEGES");
        }
    }

    public ResetResult createDatabase(String requestedDatabaseName) throws Exception {
        ensureStarted();

        String databaseName = databaseName(requestedDatabaseName);
        try (Connection connection = rootConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + databaseName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }

        return new ResetResult(databaseName, jdbcUrl(databaseName));
    }

    public List<String> tables(String requestedDatabaseName) throws Exception {
        ensureStarted();

        String databaseName = databaseName(requestedDatabaseName);
        List<String> tables = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl(databaseName), "root", "");
             ResultSet resultSet = connection.getMetaData().getTables(databaseName, null, "%", new String[] {"TABLE"})) {
            while (resultSet.next()) {
                tables.add(resultSet.getString("TABLE_NAME"));
            }
        }
        return tables;
    }

    public QueryResult query(String requestedDatabaseName, String sql) throws Exception {
        ensureStarted();

        String databaseName = databaseName(requestedDatabaseName);
        try (Connection connection = DriverManager.getConnection(jdbcUrl(databaseName), "root", "");
             Statement statement = connection.createStatement()) {
            boolean hasResultSet = statement.execute(sql);
            if (!hasResultSet) {
                return new QueryResult(List.of("Result"), List.of(List.of(statement.getUpdateCount() + " row(s) affected.")));
            }

            try (ResultSet resultSet = statement.getResultSet()) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                List<String> columns = new ArrayList<>();
                for (int column = 1; column <= metaData.getColumnCount(); column++) {
                    columns.add(metaData.getColumnLabel(column));
                }

                List<List<String>> rows = new ArrayList<>();
                while (resultSet.next() && rows.size() < 200) {
                    List<String> row = new ArrayList<>();
                    for (int column = 1; column <= metaData.getColumnCount(); column++) {
                        row.add(String.valueOf(resultSet.getObject(column)));
                    }
                    rows.add(row);
                }

                return new QueryResult(columns, rows);
            }
        }
    }

    public String jdbcUrl(String databaseName) {
        ensureStarted();
        return database.getConfiguration().getURL(databaseName);
    }

    private String rootUrl() {
        return jdbcUrl("");
    }

    private Connection rootConnection() throws Exception {
        return DriverManager.getConnection(rootUrl(), "root", "");
    }

    private void ensureStarted() {
        if (database == null) {
            throw new IllegalStateException("The local MariaDB test server is not running.");
        }
    }

    private static boolean isRecoverableDataDirectoryFailure(Exception exception, Path dataDirectory) {
        if (Files.notExists(dataDirectory.resolve("ibdata1"))) {
            return false;
        }

        String message = stackTraceMessage(exception).toLowerCase(Locale.ROOT);
        return message.contains("ibdata1")
            || message.contains("innodb")
            || message.contains("unknown/unsupported storage engine")
            || message.contains("must be writable");
    }

    private static String stackTraceMessage(Throwable throwable) {
        StringBuilder message = new StringBuilder();
        while (throwable != null) {
            if (throwable.getMessage() != null) {
                message.append(throwable.getMessage()).append('\n');
            }
            for (Throwable suppressed : throwable.getSuppressed()) {
                if (suppressed.getMessage() != null) {
                    message.append(suppressed.getMessage()).append('\n');
                }
            }
            throwable = throwable.getCause();
        }
        return message.toString();
    }

    private static String databaseName(String pluginName) {
        String normalized = pluginName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        normalized = normalized.replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (!DATABASE_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Plugin names must contain letters or numbers and fit within a 64-character database name.");
        }
        return normalized;
    }

    private static String accountName(String accountName) {
        String normalized = accountName.trim();
        if (!ACCOUNT_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Database account names may only contain letters, numbers, and underscores, up to 32 characters.");
        }
        return normalized;
    }

    private static String sqlString(String value) {
        return value.replace("\\", "\\\\").replace("'", "''");
    }

    public record ResetResult(String databaseName, String jdbcUrl) {
    }

    public record QueryResult(List<String> columns, List<List<String>> rows) {
    }
}
