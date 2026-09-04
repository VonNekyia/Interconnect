package de.petrichor.nekyia.interconnect.plugin;

import de.petrichor.nekyia.interconnect.config.DatabaseConfig;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public final class PluginVersionSyncService {
    private final Plugin plugin;
    private final DatabaseConfig config;

    public PluginVersionSyncService(Plugin plugin, DatabaseConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public SyncResult syncSelectedVersion() {
        Path targetPluginsFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize().getParent();
        if (targetPluginsFolder == null) {
            return new SyncResult(0, 0, List.of("Could not resolve the server plugins folder."));
        }

        Path serverRoot = targetPluginsFolder.getParent();
        if (serverRoot == null) {
            return new SyncResult(0, 0, List.of("Could not resolve the server root folder."));
        }

        Path sourceFolder = findSourceFolder(serverRoot);
        if (sourceFolder == null) {
            return syncShadedVersion(targetPluginsFolder);
        }

        List<String> errors = new ArrayList<>();
        int copied = 0;
        int copiedJars = 0;
        try {
            Files.createDirectories(targetPluginsFolder);
            try (Stream<Path> children = Files.list(sourceFolder)) {
                for (Path child : children.toList()) {
                    Path target = targetPluginsFolder.resolve(child.getFileName().toString());
                    if (Files.isRegularFile(child) && child.getFileName().toString().toLowerCase().endsWith(".jar")) {
                        if (copyFileIfChanged(child, target, errors)) {
                            copied++;
                            copiedJars++;
                        }
                    } else if (Files.isDirectory(child)) {
                        copied += copyDirectory(child, target, errors);
                    }
                }
            }
        } catch (IOException exception) {
            errors.add(exception.getMessage());
        }

        return new SyncResult(copied, copiedJars, List.copyOf(errors));
    }

    private Path findSourceFolder(Path serverRoot) {
        Path sourceFolder = serverRoot.resolve(config.pluginVersionFolderName()).toAbsolutePath().normalize();
        if (Files.isDirectory(sourceFolder)) {
            return sourceFolder;
        }

        if (serverRoot.getParent() == null) {
            return null;
        }

        sourceFolder = serverRoot.getParent().resolve(config.pluginVersionFolderName()).toAbsolutePath().normalize();
        if (Files.isDirectory(sourceFolder)) {
            return sourceFolder;
        }

        return null;
    }

    private SyncResult syncShadedVersion(Path targetPluginsFolder) {
        String resourcePrefix = "plugin-versions/" + config.pluginVersionFolderName() + "/";
        List<String> errors = new ArrayList<>();
        int copied = 0;
        int copiedJars = 0;

        try (JarFile jarFile = new JarFile(pluginJarPath().toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(resourcePrefix)) {
                    continue;
                }

                String relativePath = entry.getName().substring(resourcePrefix.length());
                if (relativePath.isBlank()) {
                    continue;
                }

                Path target = targetPluginsFolder.resolve(relativePath);
                try (InputStream inputStream = jarFile.getInputStream(entry)) {
                    if (copyStreamIfChanged(inputStream, target, errors)) {
                        copied++;
                        if (relativePath.toLowerCase().endsWith(".jar")) {
                            copiedJars++;
                        }
                    }
                }
            }
        } catch (IOException | URISyntaxException exception) {
            errors.add("Could not copy shaded plugin version folder " + config.pluginVersionFolderName() + ": " + exception.getMessage());
        }

        if (copied == 0 && errors.isEmpty()) {
            errors.add("Plugin version folder was not found externally or inside the plugin jar: " + config.pluginVersionFolderName());
        }

        return new SyncResult(copied, copiedJars, List.copyOf(errors));
    }

    private Path pluginJarPath() throws URISyntaxException {
        return Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private int copyDirectory(Path sourceDirectory, Path targetDirectory, List<String> errors) {
        int copied = 0;
        try (Stream<Path> paths = Files.walk(sourceDirectory)) {
            for (Path source : paths.toList()) {
                Path target = targetDirectory.resolve(sourceDirectory.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(source) && copyFileIfChanged(source, target, errors)) {
                    copied++;
                }
            }
        } catch (IOException exception) {
            errors.add("Could not copy config folder " + sourceDirectory.getFileName() + ": " + exception.getMessage());
        }
        return copied;
    }

    private boolean copyFileIfChanged(Path source, Path target, List<String> errors) {
        try {
            if (Files.exists(target) && Files.mismatch(source, target) == -1) {
                return false;
            }

            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            return true;
        } catch (IOException exception) {
            errors.add("Could not copy " + source.getFileName() + ": " + exception.getMessage());
            return false;
        }
    }

    private boolean copyStreamIfChanged(InputStream inputStream, Path target, List<String> errors) {
        try {
            byte[] sourceBytes = inputStream.readAllBytes();
            if (Files.exists(target)) {
                byte[] targetBytes = Files.readAllBytes(target);
                if (java.util.Arrays.equals(sourceBytes, targetBytes)) {
                    return false;
                }
            }

            Files.createDirectories(target.getParent());
            Files.write(target, sourceBytes);
            return true;
        } catch (IOException exception) {
            errors.add("Could not copy " + target.getFileName() + ": " + exception.getMessage());
            return false;
        }
    }

    public record SyncResult(int copiedFiles, int copiedJars, List<String> errors) {
    }
}
