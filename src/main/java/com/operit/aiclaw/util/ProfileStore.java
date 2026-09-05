package com.operit.aiclaw.util;

import com.operit.aiclaw.llm.RequestStyle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Manages the {@code ~/.aiclaw/} profile layout on disk.
 *
 * <p>Directory layout:</p>
 * <pre>
 *   ~/.aiclaw/
 *     config.properties            global config (active.profile, agents.dir, timeouts, etc.)
 *     profiles/
 *       openai.properties          one profile
 *       deepseek.properties
 *       ...
 * </pre>
 *
 * <p>Each profile file is a standard Java {@link Properties} document with an informational
 * header. Errors while reading individual profiles are silently skipped by
 * {@link #listProfiles()} so a corrupted file never blocks the rest of the directory.</p>
 */
public final class ProfileStore {

    private final Path home;          // ~/.aiclaw
    private final Path profilesDir;   // ~/.aiclaw/profiles
    private final Path globalConfig;  // ~/.aiclaw/config.properties

    public ProfileStore() {
        this(Paths.get(System.getProperty("user.home")).resolve(".aiclaw"));
    }

    public ProfileStore(Path home) {
        this.home = home;
        this.profilesDir = home.resolve("profiles");
        this.globalConfig = home.resolve("config.properties");
    }

    public Path getHome() { return home; }
    public Path getProfilesDir() { return profilesDir; }
    public Path getGlobalConfig() { return globalConfig; }

    /** Ensures both {@code ~/.aiclaw/} and {@code profiles/} exist. No-op when already present. */
    public void ensureDirectories() throws IOException {
        Files.createDirectories(home);
        Files.createDirectories(profilesDir);
    }

    /** Whether the global config file currently exists on disk. */
    public boolean globalConfigExists() {
        return Files.exists(globalConfig);
    }

    /**
     * Returns the active profile name read from {@code config.properties}, or {@code null} if no
     * active profile is selected.
     */
    public String activeProfileName() {
        if (!globalConfigExists()) return null;
        Properties properties = loadProperties(globalConfig);
        String name = properties.getProperty("active.profile");
        if (name != null && !name.isBlank()) return name.trim();
        return null;
    }

    /** Reads the global config; returns an empty {@link Properties} if it does not yet exist. */
    public Properties loadGlobal() {
        return loadProperties(globalConfig);
    }

    /** Writes the global config, prepending an explanatory header. */
    public void saveGlobal(Properties properties) throws IOException {
        ensureDirectories();
        try (OutputStream out = Files.newOutputStream(globalConfig)) {
            String header = "# aiclaw global config\n"
                    + "# active profile: switch with `aiclaw profile use <name>`\n"
                    + "# global defaults can be placed here (agents.dir / http.timeout.seconds / agent.max.tool.iterations)\n\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            properties.store(out, null);
        }
    }

    /** Sets {@code active.profile} in the global config. */
    public void setActiveProfile(String name) throws IOException {
        Properties properties = loadGlobal();
        properties.setProperty("active.profile", name);
        saveGlobal(properties);
    }

    /** Lists every profile in alphabetical order. Corrupted files are silently skipped. */
    public List<Profile> listProfiles() {
        if (!Files.exists(profilesDir)) return Collections.emptyList();
        List<Profile> profiles = new ArrayList<>();
        try (var stream = Files.list(profilesDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                    .forEach(path -> {
                        String name = stripExt(path.getFileName().toString());
                        try {
                            profiles.add(Profile.fromProperties(name, path, loadProperties(path)));
                        } catch (Exception ignored) {
                            // Skip corrupted profile files instead of failing the whole listing.
                        }
                    });
        } catch (IOException ignored) {
            // A missing or unreadable profiles directory simply yields an empty list.
        }
        return profiles;
    }

    /** Reads the profile with the given name; returns {@code null} when it does not exist. */
    public Profile get(String name) {
        if (name == null) return null;
        Path file = profilesDir.resolve(name + ".properties");
        if (!Files.exists(file)) return null;
        return Profile.fromProperties(name, file, loadProperties(file));
    }

    public boolean exists(String name) {
        return name != null && Files.exists(profilesDir.resolve(name + ".properties"));
    }

    /** Persists the supplied profile, creating or overwriting its backing file. */
    public void save(Profile profile) throws IOException {
        ensureDirectories();
        Path file = profilesDir.resolve(profile.getName() + ".properties");
        try (OutputStream out = Files.newOutputStream(file)) {
            String header = "# aiclaw profile: " + profile.getName() + "\n"
                    + "# switch with: aiclaw profile use " + profile.getName() + "\n\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            profile.toProperties().store(out, null);
        }
    }

    /** Deletes the named profile; if it was active, the active marker is cleared as well. */
    public boolean remove(String name) throws IOException {
        Path file = profilesDir.resolve(name + ".properties");
        if (!Files.exists(file)) return false;
        Files.delete(file);
        if (name.equals(activeProfileName())) {
            Properties properties = loadGlobal();
            properties.remove("active.profile");
            saveGlobal(properties);
        }
        return true;
    }

    /**
     * Renames a profile file. When the renamed profile was active, the active marker is moved
     * to the new name so {@code aiclaw profile use} keeps pointing at it.
     */
    public boolean rename(String oldName, String newName) throws IOException {
        if (oldName.equals(newName)) return true;
        Path oldFile = profilesDir.resolve(oldName + ".properties");
        Path newFile = profilesDir.resolve(newName + ".properties");
        if (!Files.exists(oldFile)) return false;
        if (Files.exists(newFile)) {
            throw new IOException("profile '" + newName + "' already exists");
        }
        Files.move(oldFile, newFile);
        if (oldName.equals(activeProfileName())) {
            setActiveProfile(newName);
        }
        return true;
    }

    /**
     * Updates a single profile and saves it. {@code null} means "do not change this field".
     * If the profile does not yet exist, a new one is created with the supplied non-null fields
     * and sensible defaults for the rest. This makes {@code set-url} / {@code set-key} usable
     * from scripts and pipes that target a brand-new profile name.
     */
    public Profile update(String name, String apiKey, String baseUrl,
                          String model, String description) throws IOException {
        return update(name, apiKey, baseUrl, model, null, null, null, null, description);
    }

    /** Full update that also supports request style and thinking fields. */
    public Profile update(String name, String apiKey, String baseUrl,
                          String model, RequestStyle style,
                          String thinkingModes, String thinkingLevel, String thinkingBudget,
                          String description) throws IOException {
        Profile existing = get(name);
        Profile updated;
        if (existing == null) {
            updated = new Profile(
                    name,
                    profilesDir.resolve(name + ".properties"),
                    apiKey != null ? apiKey : "",
                    baseUrl != null ? baseUrl : Config.DEFAULT_BASE_URL,
                    model != null ? model : Config.DEFAULT_MODEL,
                    style != null ? style : RequestStyle.OPENAI_GENERAL,
                    thinkingModes != null ? thinkingModes : "",
                    thinkingLevel != null ? thinkingLevel : "",
                    thinkingBudget != null ? thinkingBudget : "",
                    description != null ? description : ""
            );
        } else {
            updated = existing.withOverrides(apiKey, baseUrl, model, style,
                    thinkingModes, thinkingLevel, thinkingBudget, description);
        }
        save(updated);
        return updated;
    }

    // ----- helpers -----

    private static Properties loadProperties(Path file) {
        Properties properties = new Properties();
        if (!Files.exists(file)) return properties;
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException ignored) {
            // A missing or unreadable file yields an empty Properties; the caller decides what to do.
        }
        return properties;
    }

    private static String stripExt(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }
}