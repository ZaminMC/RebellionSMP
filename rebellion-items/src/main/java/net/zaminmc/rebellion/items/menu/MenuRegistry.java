package net.zaminmc.rebellion.items.menu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * DeluxeMenus-shaped {@code gui_menus} registry: each menu is an individual
 * YAML file under {@code menus/}; the module's {@code config.yml} only holds
 * the registry mapping (see the reference example in
 * docs/DELUXEMENUS_COMPATIBILITY.md).
 *
 * <p>Load rules: candidate files are fully parsed and validated before the
 * active map is swapped (atomic reload); a registry entry whose file is
 * missing/invalid keeps the previous active menu and reports the issue;
 * menu ids and file names are validated against traversal attempts.</p>
 */
public final class MenuRegistry {

    /** Menu ids: lowercase letters, digits, underscore, 1..48 chars. */
    private static final Pattern MENU_ID = Pattern.compile("[a-z0-9_]{1,48}");

    /** Menu file names: no traversal, no absolute paths, .yml only. */
    private static final Pattern FILE_NAME = Pattern.compile("[a-z0-9_]{1,48}\\.yml");

    private final Path menusDirectory;
    private final MenuLoader loader;
    private final Logger logger;
    private volatile Map<String, MenuSpec> active = Map.of();

    public MenuRegistry(Path menusDirectory, MenuLoader loader, Logger logger) {
        this.menusDirectory = menusDirectory;
        this.loader = loader;
        this.logger = logger;
    }

    /** @return the currently active, validated menus (immutable). */
    public Map<String, MenuSpec> active() {
        return active;
    }

    /**
     * Parses a candidate registry from the {@code gui_menus} section of a
     * config snapshot plus the menu files on disk.
     *
     * @param guiMenus the registry mapping (id → file name)
     * @return candidate map + issues; only valid menus are included
     */
    public LoadResult loadCandidate(Map<String, String> guiMenus) {
        List<String> issues = new ArrayList<>();
        Map<String, MenuSpec> candidate = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : guiMenus.entrySet()) {
            String id = entry.getKey();
            String file = entry.getValue();
            if (!MENU_ID.matcher(id).matches()) {
                issues.add("gui_menus." + id + ": invalid menu id (expected [a-z0-9_]{1,48})");
                continue;
            }
            if (file == null || !FILE_NAME.matcher(file.toLowerCase(Locale.ROOT)).matches()) {
                issues.add("gui_menus." + id + ": invalid file name '" + file
                        + "' (expected '<name>.yml' inside menus/)");
                continue;
            }
            Path menuFile = menusDirectory.resolve(file);
            // Defense in depth: resolve() already rejects absolute paths here,
            // but verify the final path still sits inside the menus directory.
            if (!menuFile.normalize().startsWith(menusDirectory.normalize())) {
                issues.add("gui_menus." + id + ": path escapes menus/ directory");
                continue;
            }
            if (!Files.exists(menuFile)) {
                issues.add("gui_menus." + id + ": file not found: menus/" + file);
                continue;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(Files.readString(menuFile));
            } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
                issues.add("gui_menus." + id + ": unreadable menus/" + file + ": "
                        + e.getMessage());
                continue;
            }
            MenuLoader.LoadedMenu loaded = loader.load(id, yaml);
            if (loaded.valid()) {
                candidate.put(id, loaded.spec());
            } else {
                issues.addAll(loaded.issues().stream()
                        .map(issue -> "menus/" + file + ": " + issue)
                        .toList());
            }
        }

        return new LoadResult(Map.copyOf(candidate), List.copyOf(issues));
    }

    /** Atomically swaps the active map when the candidate has no issues. */
    public boolean activate(LoadResult result) {
        if (!result.issues().isEmpty()) {
            logger.warning("Menu reload rejected (" + result.issues().size()
                    + " issue(s)); previous menus stay active:");
            result.issues().forEach(issue -> logger.warning("  - " + issue));
            return false;
        }
        this.active = result.menus();
        return true;
    }

    /** Parsed candidate. */
    public record LoadResult(Map<String, MenuSpec> menus, List<String> issues) {
    }
}
