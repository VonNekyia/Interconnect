# Interconnect

Interconnect is a Paper test-server helper plugin. It prepares a local test environment for content and plugin work without asking every contributor to install and configure MariaDB or copy versioned plugin files by hand.

## What It Does

- Starts a small embedded MariaDB server for the test server.
- Uses a dedicated default database port, `13306`, so it does not conflict with a normal local MariaDB/MySQL install on `3306`.
- Creates configured databases on startup.
- Creates configured database accounts on startup. The default account is `minecraft` / `minecraft` with root privileges for local test use.
- Provides `/db reset <plugin>` to drop and recreate a plugin database.
- Provides a local Adminer-style editor at `http://127.0.0.1:8090/`.
- Copies plugin jars and plugin config folders from the selected version folder, such as `plugins-26.2`, into the server `plugins` folder. Version folders can be shipped inside the Interconnect jar.

## Default Database Setup

The bundled `databases.yaml` is generated in `plugins/Interconnect/databases.yaml` on first startup.

```yaml
local-mariadb:
  port: 13306

accounts:
  - username: minecraft
    password: minecraft
    root: true
```

Plugins that need SQL should point to:

- Host: `localhost`
- Port: `13306`
- Username: `minecraft`
- Password: `minecraft`

`Nations` is configured to use database `nations`.

`Proficisci` is configured to use database `proficisci`.

## Plugin Version Folders

Versioned plugin folders are selected in `databases.yaml`:

```yaml
plugin-versions:
  selected: "26.2"
  folder-pattern: "plugins-%version%"
```

With this configuration Interconnect looks for `plugins-26.2` next to the server first. If it is not present there, Interconnect falls back to the version folder bundled inside its own jar at `plugin-versions/plugins-26.2`. It copies all jar files and plugin config folders into the active server's `plugins` folder. If new jars were copied, restart the server so Paper can load them.

## Commands

```text
/db list
/db adminer
/db add <database>
/db reset <plugin>
```

`/db reset <plugin>` supports configured plugin names and jar names from the selected plugin version folder.

## License

Interconnect is licensed under the MIT License. See [LICENSE](LICENSE).
