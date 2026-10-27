package net.zaminmc.rebellion.items;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import net.zaminmc.rebellion.items.identity.CustomItemLoader;
import net.zaminmc.rebellion.items.identity.CustomItemSpec;
import net.zaminmc.rebellion.items.lore.AnnotateService;
import net.zaminmc.rebellion.items.lore.LorePlanner;
import net.zaminmc.rebellion.items.menu.MenuLoader;
import net.zaminmc.rebellion.items.menu.MenuRegistry;
import net.zaminmc.rebellion.items.rarity.RarityLoader;
import net.zaminmc.rebellion.items.rarity.RarityRegistry;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Mutable runtime state of RebellionItems behind immutable snapshots.
 *
 * <p>Reload contract: candidate config/rarities/menus are parsed and
 * validated <em>fully</em> before anything is swapped; on any error the
 * previous snapshots stay active and every issue is returned to the caller.</p>
 */
public final class ItemsModuleState {

    private final Path dataDirectory;
    private final Logger logger;
    private final RarityLoader rarityLoader = new RarityLoader(RarityLoader.BUKKIT_VALIDATOR);
    private final MenuLoader menuLoader = new MenuLoader(MenuLoader.BUKKIT_VALIDATOR);
    private final CustomItemLoader customItemLoader =
            new CustomItemLoader(CustomItemLoader.BUKKIT_VALIDATOR);
    private final MenuRegistry menus;

    private volatile ItemsConfig config;
    private volatile RarityRegistry rarities;
    private volatile AnnotateService annotator;
    private volatile LorePlanner planner;
    private volatile java.util.Map<String, CustomItemSpec> customItems = java.util.Map.of();

    public ItemsModuleState(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.menus = new MenuRegistry(dataDirectory.resolve("menus"), menuLoader, logger);
    }

    /**
     * Loads everything from disk and activates the first valid state.
     *
     * @return issues; non-empty means the module must refuse to enable
     */
    public List<String> bootstrap() {
        List<String> issues = new ArrayList<>();

        // config.yml
        YamlConfiguration configYaml;
        try {
            configYaml = loadOrCreateConfig();
        } catch (Exception e) {
            issues.add("config.yml: unreadable: " + e.getMessage());
            return List.copyOf(issues);
        }
        ItemsConfig.LoadResult cfgResult = ItemsConfig.load(configYaml);
        issues.addAll(cfgResult.issues());
        if (cfgResult.config() == null) {
            return List.copyOf(issues);
        }

        // rarities.yml
        YamlConfiguration raritiesYaml;
        try {
            Path file = dataDirectory.resolve("rarities.yml");
            if (!java.nio.file.Files.exists(file)) {
                java.nio.file.Files.createDirectories(dataDirectory);
                try (var in = ItemsModuleState.class.getResourceAsStream("/rarities.yml")) {
                    java.nio.file.Files.copy(in, file);
                }
            }
            raritiesYaml = YamlConfiguration.loadConfiguration(file.toFile());
        } catch (Exception e) {
            issues.add("rarities.yml: unreadable: " + e.getMessage());
            return List.copyOf(issues);
        }
        RarityLoader.LoadedRarities raritiesResult = rarityLoader.load(raritiesYaml);
        issues.addAll(raritiesResult.issues());

        // items.yml (custom item definitions)
        try {
            Path itemsFile = dataDirectory.resolve("items.yml");
            if (!java.nio.file.Files.exists(itemsFile)) {
                try (var in = ItemsModuleState.class.getResourceAsStream("/items.yml")) {
                    java.nio.file.Files.copy(in, itemsFile);
                }
            }
            YamlConfiguration itemsYaml = YamlConfiguration.loadConfiguration(itemsFile.toFile());
            CustomItemLoader.LoadedCustomItems loadedItems = customItemLoader.load(itemsYaml);
            issues.addAll(loadedItems.issues());
            if (loadedItems.valid()) {
                customItems = loadedItems.items();
            }
        } catch (Exception e) {
            issues.add("items.yml: unreadable: " + e.getMessage());
        }

        // menus
        MenuRegistry.LoadResult menuResult = menus.loadCandidate(cfgResult.config().menuRegistry());
        issues.addAll(menuResult.issues());

        if (!issues.isEmpty()) {
            return List.copyOf(issues);
        }

        activate(cfgResult.config(), raritiesResult.registry(), menuResult);
        return List.of();
    }

    /**
     * Reload from disk; on any error the previous state stays active and the
     * issues are returned.
     */
    public List<String> reloadAll() {
        try {
            List<String> issues = bootstrap();
            if (!issues.isEmpty()) {
                logger.warning(() -> "Reload rejected with " + issues.size() + " issue(s); previous state stays active.");
            }
            return issues;
        } catch (Exception e) {
            logger.warning("Reload failed: " + e.getMessage());
            return List.of("reload failed: " + e.getMessage());
        }
    }

    private synchronized void activate(ItemsConfig cfg, RarityRegistry registry,
            MenuRegistry.LoadResult menuResult) {
        this.config = cfg;
        this.rarities = registry;
        this.planner = new LorePlanner(switch (cfg.lorePosition()) {
            case TOP -> LorePlanner.Position.TOP;
            case BOTTOM -> LorePlanner.Position.BOTTOM;
        });
        this.annotator = new AnnotateService(registry, planner, "RebellionItems", logger);
        this.menus.activate(menuResult);
    }

    private YamlConfiguration loadOrCreateConfig() throws Exception {
        Path file = dataDirectory.resolve("config.yml");
        if (!java.nio.file.Files.exists(file)) {
            java.nio.file.Files.createDirectories(dataDirectory);
            try (var in = ItemsModuleState.class.getResourceAsStream("/config.yml")) {
                java.nio.file.Files.copy(in, file);
            }
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        // Fill in anything a newer bundled default adds, without overwriting admin edits.
        try (var in = ItemsModuleState.class.getResourceAsStream("/config.yml")) {
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            yaml.setDefaults(defaults);
        }
        return yaml;
    }

    public ItemsConfig config() {
        return config;
    }

    public RarityRegistry rarities() {
        return rarities;
    }

    public AnnotateService annotator() {
        return annotator;
    }

    public MenuRegistry menus() {
        return menus;
    }

    /** @return the currently active custom item definitions (immutable). */
    public java.util.Map<String, CustomItemSpec> customItems() {
        return customItems;
    }
}
