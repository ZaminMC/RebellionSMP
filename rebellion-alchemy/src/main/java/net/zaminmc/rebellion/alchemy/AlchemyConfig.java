package net.zaminmc.rebellion.alchemy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;

/**
 * Validated snapshot of RebellionAlchemy's definition families (ADR-0019 §5).
 * Immutable; a reload swaps the whole record atomically. Every field is
 * checked at load and every issue is collected, following the established
 * RpgConfig pattern (ADR-0004 / ADR-0016).
 *
 * <h2>Families (split per master prompt §11 — the concepts deserve
 * independent editing)</h2>
 * <ul>
 *   <li><strong>brewing</strong> — the level curve scalars and the default
 *       per-brew award. Levels are derived from total XP by the pure
 *       {@link Leveling} walk, so a curve change re-derives every player on
 *       the next read.</li>
 *   <li><strong>unlocks</strong> — the two §5.7 paths as keyed definitions.
 *       POWER carries potion-type/amplifier/duration grants; EFFICIENCY
 *       carries cost-reduction/bag-capacity/auto-cooldown grants. Kind
 *       mismatch is a collected config issue (efficiency can never quietly
 *       add combat strength — the shape forbids it), prerequisites must
 *       reference existing keys and form no cycles, and required levels are
 *       checked against the curve maximum.</li>
 *   <li><strong>bags</strong> — capacity tiers the controller phase mints
 *       items against; an optional unlock key gates each tier.</li>
 *   <li><strong>auto-rules</strong> — the operator policy for player-defined
 *       auto-consumption: the trigger whitelist, HEALTH_BELOW threshold
 *       bounds, the cooldown floor, the priority range, the per-player cap
 *       and the potion-type whitelist (registry-free enum validation — the
 *       ADR-0019 research finding: PotionType resolves serverless,
 *       PotionEffectType does not).</li>
 * </ul>
 */
public record AlchemyConfig(@NotNull BrewingCurve brewing,
        @NotNull Map<String, UnlockDefinition> unlocks,
        @NotNull Map<String, BagDefinition> bags,
        @NotNull AutoPolicy autoPolicy) {

    /** Config key grammar (stable operator contract). */
    public static final Pattern KEY = Pattern.compile("[a-z0-9_]{1,32}");

    /** XP source-key grammar: the controller's deterministic brew identity. */
    public static final Pattern SOURCE_KEY = Pattern.compile("[a-z0-9_:\\-]{1,96}");

    public AlchemyConfig {
        unlocks = Map.copyOf(unlocks);
        bags = Map.copyOf(bags);
    }

    /** @return the unlock definition for a key, when configured. */
    public @NotNull UnlockDefinition unlock(@NotNull String key) {
        UnlockDefinition definition = unlocks.get(key);
        if (definition == null) {
            throw new IllegalArgumentException("no unlock definition for '" + key + "'");
        }
        return definition;
    }

    /** @return the bag definition for a key, when configured. */
    public @NotNull BagDefinition bag(@NotNull String key) {
        BagDefinition definition = bags.get(key);
        if (definition == null) {
            throw new IllegalArgumentException("no bag definition for '" + key + "'");
        }
        return definition;
    }

    public record LoadResult(AlchemyConfig config, List<String> issues) {
        public boolean valid() {
            return config != null && issues.isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /**
     * Test/operator convenience: the shipped defaults. Conservative but
     * functional — one modest POWER unlock and one modest EFFICIENCY
     * unlock, two bag tiers (the larger gated), and a conservative
     * auto-consumption policy with FALLING deliberately opt-in.
     */
    public static AlchemyConfig defaults() {
        return new AlchemyConfig(
                new BrewingCurve(30, 100L, 25L, 10L),
                Map.of(
                        "potent_swiftness", new UnlockDefinition("potent_swiftness",
                                true, UnlockKind.POWER, 5, List.of(),
                                List.of(new CostItem(Material.AMETHYST_SHARD, 8)),
                                Optional.of(PotionType.SWIFTNESS), 1, 1.5,
                                0.0, 0, 0),
                        "frugal_brewing", new UnlockDefinition("frugal_brewing",
                                true, UnlockKind.EFFICIENCY, 3, List.of(),
                                List.of(new CostItem(Material.GOLD_INGOT, 4)),
                                Optional.empty(), 0, 1.0,
                                0.10, 0, 15)),
                Map.of(
                        "satchel", new BagDefinition("satchel", true, 9,
                                Material.LEATHER, Optional.empty()),
                        "satchel_large", new BagDefinition("satchel_large", true, 18,
                                Material.LEATHER, Optional.of("frugal_brewing"))),
                new AutoPolicy(
                        new EnumMap<>(Map.of(
                                AutoTrigger.HEALTH_BELOW, true,
                                AutoTrigger.BURNING, true,
                                AutoTrigger.NETHER_ENTRY, true,
                                AutoTrigger.FALLING, false)),
                        1, 99, 60, 1, 100, 5,
                        List.of(PotionType.HEALING, PotionType.REGENERATION,
                                PotionType.FIRE_RESISTANCE, PotionType.SWIFTNESS,
                                PotionType.STRENGTH, PotionType.NIGHT_VISION,
                                PotionType.WATER_BREATHING,
                                PotionType.SLOW_FALLING)));
    }

    public static AlchemyConfig load(YamlConfiguration yaml) {
        LoadResult result = loadResult(yaml);
        if (!result.valid()) {
            throw new IllegalStateException("config invalid: " + result.issues());
        }
        return result.config();
    }

    /** @return candidate snapshot (null when invalid) plus every issue found. */
    public static LoadResult loadResult(YamlConfiguration yaml) {
        List<String> issues = new ArrayList<>();
        BrewingCurve brewing = loadBrewing(yaml, issues);
        Map<String, UnlockDefinition> unlocks = loadUnlocks(yaml, issues);
        validatePrerequisites(unlocks, issues);
        Map<String, BagDefinition> bags = loadBags(yaml, unlocks, issues);
        AutoPolicy autoPolicy = loadAutoPolicy(yaml, issues);
        if (!issues.isEmpty()) {
            return new LoadResult(null, List.copyOf(issues));
        }
        // Cross-family: required levels were checked against the curve while
        // loading; re-derive nothing here.
        return new LoadResult(new AlchemyConfig(brewing, unlocks, bags, autoPolicy),
                List.of());
    }

    // ------------------------------------------------------------------
    // Brewing curve
    // ------------------------------------------------------------------

    private static BrewingCurve loadBrewing(YamlConfiguration yaml,
            List<String> issues) {
        ConfigurationSection section = yaml.getConfigurationSection("alchemy.brewing");
        if (section == null) {
            issues.add("alchemy.brewing: missing section");
            return new BrewingCurve(30, 100, 25, 10);
        }
        int maxLevel = section.getInt("max-level", 30);
        long base = section.getLong("level-xp-base", 100L);
        long growth = section.getLong("level-xp-growth", 25L);
        long xpPerBrew = section.getLong("xp-per-brew", 10L);
        if (maxLevel < 1 || maxLevel > BrewingCurve.MAX_LEVEL) {
            issues.add("alchemy.brewing.max-level: must be 1.."
                    + BrewingCurve.MAX_LEVEL + " (got " + maxLevel + ")");
        }
        if (base < 1 || base > BrewingCurve.MAX_PARAM) {
            issues.add("alchemy.brewing.level-xp-base: must be 1.."
                    + BrewingCurve.MAX_PARAM + " (got " + base + ")");
        }
        if (growth < 0 || growth > BrewingCurve.MAX_PARAM) {
            issues.add("alchemy.brewing.level-xp-growth: must be 0.."
                    + BrewingCurve.MAX_PARAM + " (got " + growth + ")");
        }
        if (xpPerBrew < 1 || xpPerBrew > BrewingCurve.MAX_PARAM) {
            issues.add("alchemy.brewing.xp-per-brew: must be 1.."
                    + BrewingCurve.MAX_PARAM + " (got " + xpPerBrew + ")");
        }
        try {
            return new BrewingCurve(maxLevel, base, growth, xpPerBrew);
        } catch (IllegalArgumentException e) {
            return new BrewingCurve(30, 100, 25, 10);
        }
    }

    // ------------------------------------------------------------------
    // Unlocks
    // ------------------------------------------------------------------

    private static Map<String, UnlockDefinition> loadUnlocks(YamlConfiguration yaml,
            List<String> issues) {
        Map<String, UnlockDefinition> unlocks = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("alchemy.unlocks");
        if (section == null) {
            issues.add("alchemy.unlocks: missing section");
            return unlocks;
        }
        for (String key : section.getKeys(false)) {
            String path = "alchemy.unlocks." + key;
            if (!KEY.matcher(key).matches()) {
                issues.add(path + ": invalid key (expected [a-z0-9_]{1,32})");
                continue;
            }
            ConfigurationSection def = section.getConfigurationSection(key);
            if (def == null) {
                issues.add(path + ": must be a section");
                continue;
            }

            boolean enabled = def.getBoolean("enabled", true);

            UnlockKind kind = UnlockKind.parse(def.getString("kind", ""));
            if (kind == null) {
                issues.add(path + ".kind: must be one of POWER, EFFICIENCY "
                        + "(got '" + def.getString("kind") + "')");
                continue;
            }

            int requiredLevel = def.getInt("required-level", 1);
            int curveMax = yaml.getInt("alchemy.brewing.max-level", 30);
            if (requiredLevel < 1 || requiredLevel > curveMax) {
                issues.add(path + ".required-level: must be 1..max-level("
                        + curveMax + ") (got " + requiredLevel + ")");
            }

            List<String> prerequisites = def.getStringList("prerequisites");
            prerequisites = prerequisites.stream().map(String::trim)
                    .filter(s -> !s.isBlank()).toList();
            if (prerequisites.stream().anyMatch(p -> !KEY.matcher(p).matches())) {
                issues.add(path + ".prerequisites: every entry must match "
                        + "[a-z0-9_]{1,32}");
            }

            List<CostItem> cost = loadCost(path, def, issues);
            if (cost.isEmpty()) {
                issues.add(path + ".cost: at least one cost item required "
                        + "(nothing gameplay-relevant is free)");
            }

            Optional<PotionType> potionType = Optional.empty();
            int amplifierBonus = 0;
            double durationMultiplier = 1.0;
            double costReduction = 0.0;
            int bagCapacityBonus = 0;
            int autoCooldownReduction = 0;

            if (kind == UnlockKind.POWER) {
                String potionRaw = def.getString("potion-type", null);
                PotionType parsed = potionRaw == null ? null
                        : parsePotionType(potionRaw);
                if (parsed == null) {
                    issues.add(path + ".potion-type: must be a known PotionType "
                            + "(got '" + potionRaw + "')");
                } else {
                    potionType = Optional.of(parsed);
                }
                amplifierBonus = def.getInt("amplifier-bonus", 1);
                if (amplifierBonus < 0
                        || amplifierBonus > UnlockDefinition.MAX_AMPLIFIER_BONUS) {
                    issues.add(path + ".amplifier-bonus: must be 0.."
                            + UnlockDefinition.MAX_AMPLIFIER_BONUS
                            + " (got " + amplifierBonus + ")");
                }
                durationMultiplier = def.getDouble("duration-multiplier", 1.0);
                if (durationMultiplier < 1.0
                        || durationMultiplier > UnlockDefinition.MAX_DURATION_MULTIPLIER) {
                    issues.add(path + ".duration-multiplier: must be 1.0.."
                            + UnlockDefinition.MAX_DURATION_MULTIPLIER
                            + " (got " + durationMultiplier + ")");
                }
                if (def.contains("cost-reduction") || def.contains("bag-capacity-bonus")
                        || def.contains("auto-cooldown-reduction")) {
                    issues.add(path + ": EFFICIENCY fields on a POWER unlock - "
                            + "efficiency must not be a disguised power grant");
                }
            } else {
                costReduction = def.getDouble("cost-reduction", 0.0);
                if (costReduction < 0.0 || costReduction >= 1.0) {
                    issues.add(path + ".cost-reduction: must be 0.0 to below 1.0 "
                            + "- a full reduction mints free potions (got "
                            + costReduction + ")");
                }
                bagCapacityBonus = def.getInt("bag-capacity-bonus", 0);
                if (bagCapacityBonus < 0
                        || bagCapacityBonus > UnlockDefinition.MAX_BAG_CAPACITY_BONUS) {
                    issues.add(path + ".bag-capacity-bonus: must be 0.."
                            + UnlockDefinition.MAX_BAG_CAPACITY_BONUS
                            + " (got " + bagCapacityBonus + ")");
                }
                autoCooldownReduction = def.getInt("auto-cooldown-reduction", 0);
                if (autoCooldownReduction < 0
                        || autoCooldownReduction
                            > UnlockDefinition.MAX_AUTO_COOLDOWN_REDUCTION) {
                    issues.add(path + ".auto-cooldown-reduction: must be 0.."
                            + UnlockDefinition.MAX_AUTO_COOLDOWN_REDUCTION
                            + " (got " + autoCooldownReduction + ")");
                }
                if (def.contains("potion-type") || def.contains("amplifier-bonus")
                        || def.contains("duration-multiplier")) {
                    issues.add(path + ": POWER fields on an EFFICIENCY unlock - "
                            + "efficiency must not quietly add combat strength");
                }
            }

            if (issues.stream().noneMatch(i -> i.startsWith(path + ":")
                    || i.startsWith(path + "."))) {
                unlocks.put(key, new UnlockDefinition(key, enabled, kind,
                        requiredLevel, prerequisites, cost, potionType,
                        amplifierBonus, durationMultiplier, costReduction,
                        bagCapacityBonus, autoCooldownReduction));
            }
        }
        // Reference + cycle validation is a whole-family pass (below).
        return unlocks;
    }

    /** Prerequisites must reference existing keys and form no cycles. */
    private static void validatePrerequisites(Map<String, UnlockDefinition> unlocks,
            List<String> issues) {
        for (UnlockDefinition definition : unlocks.values()) {
            for (String prerequisite : definition.prerequisites()) {
                if (!unlocks.containsKey(prerequisite)) {
                    issues.add("alchemy.unlocks." + definition.key()
                            + ".prerequisites: references unknown unlock '"
                            + prerequisite + "'");
                }
            }
        }
        // Cycle detection: three-color DFS over the key graph. A cycle makes
        // every member permanently unpurchasable - a config error, not a
        // runtime state.
        Map<String, Integer> color = new LinkedHashMap<>();
        for (String key : unlocks.keySet()) {
            color.put(key, 0);
        }
        for (String key : unlocks.keySet()) {
            if (color.get(key) == 0) {
                detectCycle(unlocks, key, color, issues);
            }
        }
    }

    private static void detectCycle(Map<String, UnlockDefinition> unlocks, String key,
            Map<String, Integer> color, List<String> issues) {
        color.put(key, 1);
        for (String prerequisite : unlocks.get(key).prerequisites()) {
            Integer state = color.get(prerequisite);
            if (state == null) {
                continue; // unknown reference: reported above
            }
            if (state == 1) {
                issues.add("alchemy.unlocks." + key + ".prerequisites: prerequisite "
                        + "cycle through '" + prerequisite + "'");
            } else if (state == 0) {
                detectCycle(unlocks, prerequisite, color, issues);
            }
        }
        color.put(key, 2);
    }

    private static List<CostItem> loadCost(String path, ConfigurationSection def,
            List<String> issues) {
        List<CostItem> cost = new ArrayList<>();
        List<Map<?, ?>> raw = def.getMapList("cost");
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> entry = raw.get(i);
            Object materialRaw = entry.get("material");
            Object amountRaw = entry.get("amount");
            Material material = materialRaw == null ? null
                    : Material.matchMaterial(String.valueOf(materialRaw).trim());
            // Registry-free validation (ADR-0015 serverless lesson).
            if (material == null || material == Material.AIR
                    || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
                issues.add(path + ".cost[" + i + "].material: must be a valid "
                        + "item material (got '" + materialRaw + "')");
                continue;
            }
            int amount = 1;
            try {
                amount = Integer.parseInt(String.valueOf(amountRaw));
            } catch (NumberFormatException e) {
                issues.add(path + ".cost[" + i + "].amount: must be an integer 1.."
                        + CostItem.MAX_AMOUNT + " (got '" + amountRaw + "')");
                continue;
            }
            if (amount < 1 || amount > CostItem.MAX_AMOUNT) {
                issues.add(path + ".cost[" + i + "].amount: must be an integer 1.."
                        + CostItem.MAX_AMOUNT + " (got " + amount + ")");
                continue;
            }
            cost.add(new CostItem(material, amount));
        }
        return cost;
    }

    // ------------------------------------------------------------------
    // Bags
    // ------------------------------------------------------------------

    private static Map<String, BagDefinition> loadBags(YamlConfiguration yaml,
            Map<String, UnlockDefinition> unlocks, List<String> issues) {
        Map<String, BagDefinition> bags = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("alchemy.bags");
        if (section == null) {
            issues.add("alchemy.bags: missing section");
            return bags;
        }
        for (String key : section.getKeys(false)) {
            String path = "alchemy.bags." + key;
            if (!KEY.matcher(key).matches()) {
                issues.add(path + ": invalid key (expected [a-z0-9_]{1,32})");
                continue;
            }
            ConfigurationSection def = section.getConfigurationSection(key);
            if (def == null) {
                issues.add(path + ": must be a section");
                continue;
            }

            boolean enabled = def.getBoolean("enabled", true);

            int capacity = def.getInt("capacity", 9);
            if (capacity < 1 || capacity > BagDefinition.MAX_CAPACITY) {
                issues.add(path + ".capacity: must be 1.." + BagDefinition.MAX_CAPACITY
                        + " (got " + capacity + ")");
            }

            String materialRaw = def.getString("material", "LEATHER");
            Material material = Material.matchMaterial(materialRaw.trim());
            if (material == null || material == Material.AIR
                    || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
                issues.add(path + ".material: must be a valid item material (got '"
                        + materialRaw + "')");
            }

            String requiredUnlock = def.getString("required-unlock", "");
            Optional<String> gate = Optional.empty();
            if (requiredUnlock != null && !requiredUnlock.isBlank()) {
                String trimmed = requiredUnlock.trim();
                if (!KEY.matcher(trimmed).matches()) {
                    issues.add(path + ".required-unlock: must match [a-z0-9_]{1,32} "
                            + "(got '" + trimmed + "')");
                } else if (!unlocks.containsKey(trimmed)) {
                    issues.add(path + ".required-unlock: references unknown unlock '"
                            + trimmed + "'");
                } else {
                    gate = Optional.of(trimmed);
                }
            }

            if (issues.stream().noneMatch(i -> i.startsWith(path + ":")
                    || i.startsWith(path + "."))) {
                bags.put(key, new BagDefinition(key, enabled, capacity, material,
                        gate));
            }
        }
        return bags;
    }

    // ------------------------------------------------------------------
    // Auto-consumption policy
    // ------------------------------------------------------------------

    private static AutoPolicy loadAutoPolicy(YamlConfiguration yaml,
            List<String> issues) {
        ConfigurationSection section = yaml.getConfigurationSection("alchemy.auto-rules");
        if (section == null) {
            issues.add("alchemy.auto-rules: missing section");
            return defaults().autoPolicy();
        }

        int maxRules = section.getInt("max-rules-per-player", 5);
        if (maxRules < 1 || maxRules > AutoPolicy.ABSOLUTE_MAX_RULES) {
            issues.add("alchemy.auto-rules.max-rules-per-player: must be 1.."
                    + AutoPolicy.ABSOLUTE_MAX_RULES + " (got " + maxRules + ")");
        }

        int minCooldown = section.getInt("min-cooldown-seconds", 60);
        if (minCooldown < 1 || minCooldown > AutoPolicy.MAX_COOLDOWN_SECONDS) {
            issues.add("alchemy.auto-rules.min-cooldown-seconds: must be 1.."
                    + AutoPolicy.MAX_COOLDOWN_SECONDS + " (got " + minCooldown + ")");
        }

        int minPriority = section.getInt("min-priority", 1);
        int maxPriority = section.getInt("max-priority", 100);
        if (minPriority < AutoPolicy.ABSOLUTE_MIN_PRIORITY
                || minPriority > AutoPolicy.ABSOLUTE_MAX_PRIORITY) {
            issues.add("alchemy.auto-rules.min-priority: must be "
                    + AutoPolicy.ABSOLUTE_MIN_PRIORITY + ".."
                    + AutoPolicy.ABSOLUTE_MAX_PRIORITY + " (got " + minPriority + ")");
        }
        if (maxPriority < AutoPolicy.ABSOLUTE_MIN_PRIORITY
                || maxPriority > AutoPolicy.ABSOLUTE_MAX_PRIORITY
                || maxPriority < minPriority) {
            issues.add("alchemy.auto-rules.max-priority: must be "
                    + AutoPolicy.ABSOLUTE_MIN_PRIORITY + ".."
                    + AutoPolicy.ABSOLUTE_MAX_PRIORITY + " and >= min-priority "
                    + "(got " + maxPriority + ")");
        }

        Map<AutoTrigger, Boolean> triggers = new EnumMap<>(AutoTrigger.class);
        ConfigurationSection triggerSection = section.getConfigurationSection("triggers");
        if (triggerSection == null) {
            issues.add("alchemy.auto-rules.triggers: missing section - the trigger "
                    + "whitelist must be explicit");
        } else {
            for (AutoTrigger trigger : AutoTrigger.values()) {
                ConfigurationSection entry =
                        triggerSection.getConfigurationSection(trigger.name());
                if (entry == null) {
                    issues.add("alchemy.auto-rules.triggers." + trigger.name()
                            + ": missing - every trigger must be explicitly enabled "
                            + "or disabled");
                    continue;
                }
                triggers.put(trigger, entry.getBoolean("enabled", false));
                if (trigger == AutoTrigger.HEALTH_BELOW) {
                    int healthMin = entry.getInt("min-threshold", 1);
                    int healthMax = entry.getInt("max-threshold", 99);
                    if (healthMin < 1 || healthMin > 100) {
                        issues.add("alchemy.auto-rules.triggers.HEALTH_BELOW"
                                + ".min-threshold: must be 1..100 (got " + healthMin + ")");
                    }
                    if (healthMax < 1 || healthMax > 100 || healthMax < healthMin) {
                        issues.add("alchemy.auto-rules.triggers.HEALTH_BELOW"
                                + ".max-threshold: must be 1..100 and >= min-threshold "
                                + "(got " + healthMax + ")");
                    }
                }
            }
            for (String raw : triggerSection.getKeys(false)) {
                if (AutoTrigger.parse(raw) == null) {
                    issues.add("alchemy.auto-rules.triggers." + raw
                            + ": unknown trigger (known: HEALTH_BELOW, BURNING, "
                            + "NETHER_ENTRY, FALLING)");
                }
            }
        }

        List<String> potionRaw = section.getStringList("allowed-potion-types");
        List<PotionType> potions = new ArrayList<>();
        for (String raw : potionRaw) {
            PotionType parsed = parsePotionType(raw);
            if (parsed == null) {
                issues.add("alchemy.auto-rules.allowed-potion-types: unknown "
                        + "PotionType '" + raw.trim() + "'");
            } else if (!potions.contains(parsed)) {
                potions.add(parsed);
            }
        }
        if (potions.isEmpty()) {
            issues.add("alchemy.auto-rules.allowed-potion-types: at least one "
                    + "potion required (an empty whitelist would make every "
                    + "auto-rule definition fail)");
        }

        if (!issues.isEmpty()) {
            return defaults().autoPolicy();
        }
        ConfigurationSection healthEntry =
                triggerSection.getConfigurationSection(AutoTrigger.HEALTH_BELOW.name());
        return new AutoPolicy(triggers,
                healthEntry.getInt("min-threshold", 1),
                healthEntry.getInt("max-threshold", 99),
                minCooldown, minPriority, maxPriority, maxRules, potions);
    }

    /** Enum-constant lookup (registry-free, the ADR-0019 research finding). */
    private static PotionType parsePotionType(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return PotionType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
