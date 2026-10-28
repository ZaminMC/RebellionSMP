package net.zaminmc.rebellion.items;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
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

    /** Mirrors MenuRegistry's file-name rule (traversal-safe, .yml only). */
    private static final java.util.regex.Pattern MENUS_FILE_NAME =
            java.util.regex.Pattern.compile("[a-z0-9_]{1,48}\\.yml");

    private volatile ItemsConfig config;
    private volatile RarityRegistry rarities;
    private volatile AnnotateService annotator;
    private volatile LorePlanner planner;
    private volatile MessageCatalog messages;
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

        // messages.yml (ADR-0012): unreadable = fatal; missing keys or invalid
        // templates are warnings — the bundled default fills in so the module
        // still boots. Loaded first so nothing below renders through a catalog
        // that is not active yet; assigned only in activate() so a failed
        // bootstrap/reload can never half-apply messages.
        MessageCatalog.LoadResult messageResult;
        try {
            messageResult = MessageCatalog.load(dataDirectory.resolve("messages.yml"),
                    "messages.yml", ItemsModuleState.class, logger);
        } catch (MessageCatalog.MessageFileException e) {
            issues.add("messages.yml: unreadable: " + e.getMessage());
            return List.copyOf(issues);
        }
        messageResult.issues().forEach(issue -> logger.warning("messages.yml: " + issue));

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

        // menus: copy bundled defaults into the data folder when missing —
        // never over admin edits (same no-copy-over contract as config.yml).
        // Extraction is keyed by the config's registry mapping; a file the jar
        // does not carry (admin-authored menu) is left untouched and reported
        // as "file not found" by the registry, which is the correct signal.
        // Guards mirror MenuRegistry's FILE_NAME rule so an invalid registry
        // entry can never steer extraction outside the menus directory.
        java.nio.file.Path menusDirectory = dataDirectory.resolve("menus");
        for (String file : cfgResult.config().menuRegistry().values()) {
            if (file == null
                    || !MENUS_FILE_NAME.matcher(file.toLowerCase(java.util.Locale.ROOT)).matches()) {
                continue;
            }
            java.nio.file.Path target = menusDirectory.resolve(file);
            if (!target.normalize().startsWith(menusDirectory.normalize())
                    || java.nio.file.Files.exists(target)) {
                continue;
            }
            try (var in = ItemsModuleState.class.getResourceAsStream("/menus/" + file)) {
                if (in != null) {
                    java.nio.file.Files.createDirectories(menusDirectory);
                    java.nio.file.Files.copy(in, target);
                }
            } catch (java.io.IOException e) {
                issues.add("menus/" + file + ": bundled copy failed: " + e.getMessage());
            }
        }

        MenuRegistry.LoadResult menuResult = menus.loadCandidate(cfgResult.config().menuRegistry());
        issues.addAll(menuResult.issues());

        if (!issues.isEmpty()) {
            return List.copyOf(issues);
        }

        activate(cfgResult.config(), raritiesResult.registry(), menuResult, messageResult.catalog());
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
            MenuRegistry.LoadResult menuResult, MessageCatalog newMessages) {
        this.config = cfg;
        this.rarities = registry;
        this.messages = newMessages;
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
        // Proven against the pinned paper-api 26.3 (session 15 probe):
        // setDefaults alone changes NOTHING for section enumeration, and
        // copyDefaults(true) merges new bundled SECTION KEYS (gui_menus.<id>)
        // into getKeys while scalar reads still fall back to their method-arg
        // default, never to the bundled value — so admin-owned values keep
        // full sovereignty, and this config is never saved back to disk, so
        // copyDefaults' save-side copying can never touch the admin file.
        // Net effect: upgrades gain newly shipped menus automatically; the
        // on-disk file is never modified.
        try (var in = ItemsModuleState.class.getResourceAsStream("/config.yml")) {
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            yaml.setDefaults(defaults);
            yaml.options().copyDefaults(true);
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

    /** @return the active message catalog (swapped atomically by reload). */
    public MessageCatalog messages() {
        return messages;
    }

    /** @return the currently active custom item definitions (immutable). */
    public java.util.Map<String, CustomItemSpec> customItems() {
        return customItems;
    }
}
