package com.operit.aiclaw.cli;

import com.operit.aiclaw.util.Profile;
import com.operit.aiclaw.util.ProfileStore;

import java.io.IOException;
import java.util.List;

/**
 * `aiclaw profile ...` subcommands.
 *
 * <pre>
 *   aiclaw profile list              list all profiles (current marked with a star)
 *   aiclaw profile show &lt;name&gt;       show one profile in detail (API key masked)
 *   aiclaw profile use &lt;name&gt;        switch the active profile
 *   aiclaw profile add [name]        interactive creation (reuses the setup wizard)
 *   aiclaw profile remove &lt;name&gt;     delete
 *   aiclaw profile rename &lt;old&gt; &lt;new&gt;
 *   aiclaw profile set-key &lt;name&gt; &lt;key&gt;
 *   aiclaw profile set-url &lt;name&gt; &lt;url&gt;
 *   aiclaw profile set-model &lt;name&gt; &lt;model&gt;
 *   aiclaw profile set-desc &lt;name&gt; &lt;desc...&gt;
 *   aiclaw profile where             show the configuration file locations
 * </pre>
 */
public class ProfileCommand {

    private final ProfileStore store;

    public ProfileCommand() {
        this.store = new ProfileStore();
    }

    public ProfileCommand(ProfileStore store) {
        this.store = store;
    }

    public int run(String[] args) {
        if (args.length == 0) {
            printUsage();
            return 0;
        }
        String sub = args[0];
        try {
            switch (sub) {
                case "list":
                case "ls": return cmdList();
                case "show": return cmdShow(rest(args, 1));
                case "use":  return cmdUse(rest(args, 1));
                case "add":  return cmdAdd(rest(args, 1));
                case "remove":
                case "rm":
                case "delete": return cmdRemove(rest(args, 1));
                case "rename":
                case "mv":     return cmdRename(rest(args, 1));
                case "set-key":   return cmdSetKey(rest(args, 1));
                case "set-url":   return cmdSetUrl(rest(args, 1));
                case "set-model": return cmdSetModel(rest(args, 1));
                case "set-desc":  return cmdSetDesc(rest(args, 1));
                case "where":
                case "dir":       return cmdWhere();
                case "help":
                case "-h":
                case "--help":    printUsage(); return 0;
                default:
                    System.err.println("Unknown profile subcommand: " + sub);
                    printUsage();
                    return 2;
            }
        } catch (IOException e) {
            System.err.println("Profile command failed: " + e.getMessage());
            return 2;
        }
    }

    private static String[] rest(String[] args, int from) {
        if (from >= args.length) return new String[0];
        String[] r = new String[args.length - from];
        System.arraycopy(args, from, r, 0, r.length);
        return r;
    }

    private int cmdList() throws IOException {
        store.ensureDirectories();
        List<Profile> all = store.listProfiles();
        String active = store.activeProfileName();
        if (all.isEmpty()) {
            System.out.println("(no profile)");
            System.out.println("Run `aiclaw setup` to create one, or `aiclaw profile add <name>`.");
            return 0;
        }
        System.out.printf("%-18s %-10s %-22s %-22s %s%n",
                "NAME", "ACTIVE", "MODEL", "BASE_URL", "API_KEY");
        System.out.println("-".repeat(95));
        for (Profile p : all) {
            System.out.printf("%-18s %-10s %-22s %-22s %s%n",
                    truncate(p.getName(), 18),
                    p.getName().equals(active) ? "*" : "",
                    truncate(p.getModel() == null ? "" : p.getModel(), 22),
                    truncate(p.getBaseUrl() == null ? "" : p.getBaseUrl(), 22),
                    p.maskedKey());
        }
        System.out.println();
        System.out.println("Config dir : " + store.getHome());
        System.out.println("Active     : " + (active == null ? "(none)" : active));
        return 0;
    }

    private int cmdShow(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: aiclaw profile show <name>");
            return 2;
        }
        Profile p = store.get(args[0]);
        if (p == null) {
            System.err.println("profile not found: " + args[0]);
            return 2;
        }
        System.out.println("Name        : " + p.getName());
        System.out.println("Description : " + (p.getDescription() == null || p.getDescription().isEmpty() ? "(none)" : p.getDescription()));
        System.out.println("Base URL    : " + p.getBaseUrl());
        System.out.println("Model       : " + p.getModel());
        System.out.println("API Key     : " + p.maskedKey());
        System.out.println("Thinking    : " + (p.getThinkingModes().isBlank() ? "(none)" : p.getThinkingModes()));
        System.out.println("Ctrl+A      : " + (p.getThinkingControls().isEmpty()
                ? "(none)" : p.getThinkingControls().modes().keySet()));
        System.out.println("File        : " + p.getFile());
        System.out.println("Active      : " + (args[0].equals(store.activeProfileName()) ? "yes" : "no"));
        return 0;
    }

    private int cmdUse(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: aiclaw profile use <name>");
            return 2;
        }
        String name = args[0];
        if (!store.exists(name)) {
            System.err.println("profile not found: " + name);
            System.err.println("Run `aiclaw profile list` to see available profiles.");
            return 2;
        }
        store.setActiveProfile(name);
        Profile p = store.get(name);
        System.out.println("Switched active profile -> " + name);
        System.out.println("   base_url = " + p.getBaseUrl());
        System.out.println("   model    = " + p.getModel());
        System.out.println("   api_key  = " + p.maskedKey());
        return 0;
    }

    private int cmdAdd(String[] args) {
        // `add` reuses the setup wizard to walk the user through another profile.
        return new Setup(false).run();
    }

    private int cmdRemove(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: aiclaw profile remove <name>");
            return 2;
        }
        String name = args[0];
        if (!store.exists(name)) {
            System.err.println("profile not found: " + name);
            return 2;
        }
        boolean wasActive = name.equals(store.activeProfileName());
        store.remove(name);
        System.out.println("Removed profile: " + name);
        if (wasActive) System.out.println("Warning: it was the active profile; the active marker has been cleared.");
        return 0;
    }

    private int cmdRename(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: aiclaw profile rename <old> <new>");
            return 2;
        }
        store.rename(args[0], args[1]);
        System.out.println("Renamed: " + args[0] + " -> " + args[1]);
        return 0;
    }

    private int cmdSetKey(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: aiclaw profile set-key <name> <key>");
            return 2;
        }
        store.update(args[0], args[1], null, null, null);
        System.out.println("Updated api.key on '" + args[0] + "'");
        return 0;
    }

    private int cmdSetUrl(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: aiclaw profile set-url <name> <url>");
            return 2;
        }
        store.update(args[0], null, args[1], null, null);
        System.out.println("Updated api.base.url on '" + args[0] + "'");
        return 0;
    }

    private int cmdSetModel(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: aiclaw profile set-model <name> <model>");
            return 2;
        }
        store.update(args[0], null, null, args[1], null);
        System.out.println("Updated api.model on '" + args[0] + "'");
        return 0;
    }

    private int cmdSetDesc(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: aiclaw profile set-desc <name> <desc...>");
            return 2;
        }
        store.update(args[0], null, null, null, joinRange(args, 1, args.length));
        System.out.println("Updated description on '" + args[0] + "'");
        return 0;
    }

    private int cmdWhere() {
        System.out.println("Home dir       : " + store.getHome());
        System.out.println("Profiles dir   : " + store.getProfilesDir());
        System.out.println("Global config  : " + store.getGlobalConfig());
        return 0;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "...";
    }

    private static String joinRange(String[] arr, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to && i < arr.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private void printUsage() {
        System.out.println(String.join("\n",
                "aiclaw profile - manage LLM profiles",
                "",
                "USAGE",
                "  aiclaw profile list",
                "  aiclaw profile show <name>",
                "  aiclaw profile use <name>",
                "  aiclaw profile add                  # interactive creation (same as setup)",
                "  aiclaw profile remove <name>",
                "  aiclaw profile rename <old> <new>",
                "  aiclaw profile set-key <name> <key>",
                "  aiclaw profile set-url <name> <url>",
                "  aiclaw profile set-model <name> <model>",
                "  aiclaw profile set-desc <name> <desc...>",
                "  aiclaw profile where                # show configuration file locations",
                "",
                "FILES",
                "  ~/.aiclaw/config.properties        active profile + global defaults",
                "  ~/.aiclaw/profiles/<name>.properties  one profile's connection parameters"
        ));
    }
}