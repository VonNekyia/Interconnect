package de.petrichor.nekyia.interconnect.database;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.petrichor.nekyia.interconnect.config.DatabaseConfig;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class AdminerEditorServer {
    private final Plugin plugin;
    private final DatabaseConfig config;
    private final LocalMariaDbService databaseService;
    private HttpServer server;

    public AdminerEditorServer(Plugin plugin, DatabaseConfig config, LocalMariaDbService databaseService) {
        this.plugin = plugin;
        this.config = config;
        this.databaseService = databaseService;
    }

    public void start() throws IOException {
        if (!config.adminerEditorEnabled()) {
            plugin.getLogger().info("Adminer Editor is disabled.");
            return;
        }

        server = HttpServer.create(new InetSocketAddress(config.adminerEditorHost(), config.adminerEditorPort()), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        plugin.getLogger().info("Adminer Editor started at " + url() + ".");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public String url() {
        return "http://" + config.adminerEditorHost() + ":" + config.adminerEditorPort() + "/";
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && "/databases".equals(path)) {
                createDatabase(exchange);
                return;
            }

            if ("POST".equals(exchange.getRequestMethod()) && path.startsWith("/query/")) {
                query(exchange, path.substring("/query/".length()));
                return;
            }

            if (path.startsWith("/database/")) {
                database(exchange, path.substring("/database/".length()), null);
                return;
            }

            index(exchange);
        } catch (Exception exception) {
            page(exchange, "Adminer Editor", "<p class=\"error\">" + escape(exception.getMessage()) + "</p>");
        }
    }

    private void index(HttpExchange exchange) throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("<form method=\"post\" action=\"/databases\"><input name=\"name\" placeholder=\"database name\"><button>Add database</button></form>");
        body.append("<h2>Databases</h2><ul>");
        for (String database : config.allDatabaseNames()) {
            String normalized = normalize(database);
            body.append("<li><a href=\"/database/").append(escapeUrl(normalized)).append("\">").append(escape(normalized)).append("</a></li>");
        }
        body.append("</ul>");
        page(exchange, "Adminer Editor", body.toString());
    }

    private void createDatabase(HttpExchange exchange) throws Exception {
        Map<String, String> form = form(exchange);
        LocalMariaDbService.ResetResult result = databaseService.createDatabase(form.getOrDefault("name", ""));
        config.addDatabase(result.databaseName());
        redirect(exchange, "/database/" + escapeUrl(result.databaseName()));
    }

    private void database(HttpExchange exchange, String rawDatabaseName, LocalMariaDbService.QueryResult queryResult) throws Exception {
        String databaseName = normalize(rawDatabaseName);
        if (config.allDatabaseNames().stream().noneMatch(database -> normalize(database).equals(databaseName))) {
            throw new IllegalArgumentException("Database is not configured in databases.yaml: " + databaseName);
        }

        StringBuilder body = new StringBuilder();
        body.append("<p><a href=\"/\">Databases</a></p>");
        body.append("<p><code>").append(escape(databaseService.jdbcUrl(databaseName))).append("</code></p>");
        body.append("<h2>Tables</h2><ul>");
        for (String table : databaseService.tables(databaseName)) {
            body.append("<li>").append(escape(table)).append("</li>");
        }
        body.append("</ul>");
        body.append("<h2>SQL</h2><form method=\"post\" action=\"/query/").append(escapeUrl(databaseName)).append("\">");
        body.append("<textarea name=\"sql\" spellcheck=\"false\">SELECT * FROM information_schema.tables WHERE table_schema = DATABASE() LIMIT 50;</textarea>");
        body.append("<button>Run SQL</button></form>");

        if (queryResult != null) {
            body.append("<h2>Result</h2><table><thead><tr>");
            for (String column : queryResult.columns()) {
                body.append("<th>").append(escape(column)).append("</th>");
            }
            body.append("</tr></thead><tbody>");
            for (List<String> row : queryResult.rows()) {
                body.append("<tr>");
                for (String value : row) {
                    body.append("<td>").append(escape(value)).append("</td>");
                }
                body.append("</tr>");
            }
            body.append("</tbody></table>");
        }

        page(exchange, databaseName + " - Adminer Editor", body.toString());
    }

    private void query(HttpExchange exchange, String rawDatabaseName) throws Exception {
        Map<String, String> form = form(exchange);
        database(exchange, rawDatabaseName, databaseService.query(rawDatabaseName, form.getOrDefault("sql", "")));
    }

    private Map<String, String> form(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> values = new HashMap<>();
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            values.put(key, value);
        }
        return values;
    }

    private void page(HttpExchange exchange, String title, String body) throws IOException {
        String html = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>%s</title>
              <style>
                body { margin: 0; font: 14px system-ui, sans-serif; color: #202124; background: #f6f7f8; }
                main { max-width: 1100px; margin: 0 auto; padding: 24px; }
                h1, h2 { font-size: 20px; margin: 0 0 16px; }
                h2 { font-size: 16px; margin-top: 28px; }
                a { color: #0b57d0; }
                form { display: flex; gap: 8px; align-items: start; margin-bottom: 16px; }
                input, textarea { border: 1px solid #c4c7c5; border-radius: 4px; padding: 8px; font: inherit; background: white; }
                input { min-width: 260px; }
                textarea { width: 100%%; min-height: 160px; font-family: Consolas, monospace; }
                button { border: 1px solid #0b57d0; border-radius: 4px; padding: 8px 12px; color: white; background: #0b57d0; cursor: pointer; }
                table { border-collapse: collapse; width: 100%%; background: white; }
                th, td { border: 1px solid #dadce0; padding: 6px 8px; text-align: left; vertical-align: top; }
                code { background: white; border: 1px solid #dadce0; border-radius: 4px; padding: 4px 6px; }
                .error { color: #b3261e; }
              </style>
            </head>
            <body><main><h1>%s</h1>%s</main></body>
            </html>
            """.formatted(escape(title), escape(title), body);
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void redirect(HttpExchange exchange, String path) throws IOException {
        exchange.getResponseHeaders().set("Location", path);
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    private static String normalize(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8).toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
    }

    private static String escape(String value) {
        return String.valueOf(value)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private static String escapeUrl(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
