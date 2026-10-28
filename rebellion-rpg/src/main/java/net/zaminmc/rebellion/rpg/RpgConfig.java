package net.zaminmc.rebellion.rpg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

/**
 * Validated snapshot of RebellionRPG's definition families (ADR-0016 §5).
 * Immutable; a reload swaps the whole record atomically. Every field is
 * checked at load and every issue is collected, following the established
 * ArtifactsConfig / DragonsConfig pattern (ADR-0004).
 *
 * <h2>Families (split per master prompt §11 — the concepts deserve
 * independent editing)</h2>
 * <ul>
 *   <li><strong>rituals</strong> — keyed definitions with kind
 *       (TRACKING / COUNTER_MASK / KNOWLEDGE), ingredients, cooldown,
 *       rate limit, duration, precision ceiling, failure chance and the
 *       tracking session cap. KNOWLEDGE rituals name the discovery key
 *       they unlock.</li>
 *   <li><strong>encounters</strong> — keyed rare-soul definitions with
 *       spawn rules (worlds, biomes, time window, player counts), the
 *       ledger rules the service enforces (max-concurrent, cooldown,
 *       TTL) and the reward discovery keys. The shipped default is
 *       <em>disabled</em>: spawn rules are world data the operator
 *       calibrates on import (the wards calibrated-scale precedent —
 *       nothing auto-spawns from a stock install).</li>
 * </ul>
 */
public record RpgConfig(@NotNull Map<String, RitualDefinition> rituals,
        @NotNull Map<String, EncounterDefinition> encounters) {

    /** Config key grammar (stable operator contract). */
    public static final Pattern KEY = Pattern.compile("[a-z0-9_]{1,32}");

    public RpgConfig {
        rituals = Map.copyOf(rituals);
        encounters = Map.copyOf(encounters);
    }

    /** @return the ritual definition for a key, when configured. */
    public @NotNull RitualDefinition ritual(@NotNull String key) {
        RitualDefinition definition = rituals.get(key);
        if (definition == null) {
            throw new IllegalArgumentException("no ritual definition for '" + key + "'");
        }
        return definition;
    }

    /** @return the encounter definition for a key, when configured. */
    public @NotNull EncounterDefinition encounter(@NotNull String key) {
        EncounterDefinition definition = encounters.get(key);
        if (definition == null) {
            throw new IllegalArgumentException("no encounter definition for '" + key + "'");
        }
        return definition;
    }

    public record LoadResult(RpgConfig config, List<String> issues) {
        public boolean valid() {
            return config != null && issues.isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /**
     * Test/operator convenience: the shipped defaults. Conservative but
     * functional — one enabled tracking ritual, one enabled mask
     * counter-ritual, one knowledge ritual, and one encounter definition
     * that is disabled until the operator calibrates its spawn rules.
     */
    public static RpgConfig defaults() {
        return new RpgConfig(
                Map.of(
                        "tracking", new RitualDefinition("tracking", true,
                                RitualKind.TRACKING,
                                List.of(new Ingredient(Material.ECHO_SHARD, 4),
                                        new Ingredient(Material.DIAMOND, 2)),
                                3600L, 3, 21_600L, 1800L, Precision.REGION,
                                0.25, 1, java.util.Optional.empty()),
                        "mask", new RitualDefinition("mask", true,
                                RitualKind.COUNTER_MASK,
                                List.of(new Ingredient(Material.AMETHYST_SHARD, 2)),
                                7200L, 2, 21_600L, 3600L, Precision.REGION,
                                0.0, 1, java.util.Optional.empty()),
                        "soul_lore", new RitualDefinition("soul_lore", true,
                                RitualKind.KNOWLEDGE,
                                List.of(new Ingredient(Material.BOOK, 1),
                                        new Ingredient(Material.LAPIS_LAZULI, 8)),
                                86_400L, 1, 86_400L, 30L, Precision.REGION,
                                0.0, 1, java.util.Optional.of("ritual_soul_communion"))),
                Map.of("wandering_soul", new EncounterDefinition("wandering_soul",
                        false, org.bukkit.entity.EntityType.ALLAY, List.of("world"),
                        List.of(),
                        EncounterDefinition.TimeWindow.NIGHT,
                        1, 0, 1, 86_400L, 1800L,
                        List.of("ritual_soul_communion"))));
    }

    public static RpgConfig load(YamlConfiguration yaml) {
        LoadResult result = loadResult(yaml);
        if (!result.valid()) {
            throw new IllegalStateException("config invalid: " + result.issues());
        }
        return result.config();
    }

    /** @return candidate snapshot (null when invalid) plus every issue found. */
    public static LoadResult loadResult(YamlConfiguration yaml) {
        List<String> issues = new ArrayList<>();
        Map<String, RitualDefinition> rituals = loadRituals(yaml, issues);
        Map<String, EncounterDefinition> encounters = loadEncounters(yaml, issues);
        RpgConfig config = issues.isEmpty()
                ? new RpgConfig(rituals, encounters) : null;
        return new LoadResult(config, List.copyOf(issues));
    }

    // ------------------------------------------------------------------
    // Rituals
    // ------------------------------------------------------------------

    private static Map<String, RitualDefinition> loadRituals(YamlConfiguration yaml,
            List<String> issues) {
        Map<String, RitualDefinition> rituals = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("rpg.rituals");
        if (section == null) {
            issues.add("rpg.rituals: missing section");
            return rituals;
        }
        for (String key : section.getKeys(false)) {
            String path = "rpg.rituals." + key;
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

            RitualKind kind = RitualKind.parse(def.getString("kind", ""));
            if (kind == null) {
                issues.add(path + ".kind: must be one of TRACKING, COUNTER_MASK, "
                        + "KNOWLEDGE (got '" + def.getString("kind") + "')");
                continue;
            }

            List<Ingredient> ingredients = loadIngredients(path, def, issues);
            if (ingredients.isEmpty()) {
                issues.add(path + ".ingredients: at least one ingredient required");
            }

            long cooldown = def.getLong("cooldown-seconds", 3600L);
            if (cooldown < 0 || cooldown > RitualDefinition.MAX_SECONDS) {
                issues.add(path + ".cooldown-seconds: must be between 0 and "
                        + RitualDefinition.MAX_SECONDS + " (got " + cooldown + ")");
            }

            int attempts = def.getInt("rate-limit.attempts", 3);
            long window = def.getLong("rate-limit.window-seconds", 21_600L);
            if (attempts < 1 || attempts > RitualDefinition.MAX_RATE_ATTEMPTS) {
                issues.add(path + ".rate-limit.attempts: must be between 1 and "
                        + RitualDefinition.MAX_RATE_ATTEMPTS + " (got " + attempts + ")");
            }
            if (window < 1 || window > RitualDefinition.MAX_RATE_WINDOW_SECONDS) {
                issues.add(path + ".rate-limit.window-seconds: must be between 1 and "
                        + RitualDefinition.MAX_RATE_WINDOW_SECONDS + " (got " + window + ")");
            }

            long duration = def.getLong("duration-seconds", 1800L);
            if (duration < RitualDefinition.MIN_DURATION_SECONDS
                    || duration > RitualDefinition.MAX_DURATION_SECONDS) {
                issues.add(path + ".duration-seconds: must be between "
                        + RitualDefinition.MIN_DURATION_SECONDS + " and "
                        + RitualDefinition.MAX_DURATION_SECONDS + " (got " + duration + ")");
            }

            Precision precision = Precision.parse(
                    def.getString("precision", Precision.REGION.name()));
            if (precision == null) {
                issues.add(path + ".precision: must be one of DIMENSION, REGION, "
                        + "COORDINATE (got '" + def.getString("precision") + "')");
            }

            // Default 0.0: only TRACKING rolls, and the shipped tracking
            // default sets its 0.25 explicitly (kept in sync by the test).
            double failure = def.getDouble("failure-chance", 0.0);
            if (failure < 0.0 || failure >= RitualDefinition.MAX_FAILURE_CHANCE) {
                issues.add(path + ".failure-chance: must be 0.0 to below "
                        + RitualDefinition.MAX_FAILURE_CHANCE
                        + " - a guaranteed-fail ritual is config nonsense (got "
                        + failure + ")");
            }

            int maxSessions = def.getInt("max-sessions", 1);
            if (maxSessions < 1 || maxSessions > RitualDefinition.MAX_SESSIONS) {
                issues.add(path + ".max-sessions: must be between 1 and "
                        + RitualDefinition.MAX_SESSIONS + " (got " + maxSessions + ")");
            }

            String knowledgeKey = def.getString("knowledge-key", null);
            if (kind == RitualKind.KNOWLEDGE) {
                if (knowledgeKey == null || !KEY.matcher(knowledgeKey.trim()).matches()) {
                    issues.add(path + ".knowledge-key: required for KNOWLEDGE rituals "
                            + "and must match [a-z0-9_]{1,32} (got '" + knowledgeKey + "')");
                }
            } else if (knowledgeKey != null) {
                issues.add(path + ".knowledge-key: only KNOWLEDGE rituals may carry a "
                        + "knowledge key (kind is " + kind + ")");
            }

            if (issues.stream().noneMatch(i -> i.startsWith(path + ":")
                    || i.startsWith(path + "."))) {
                rituals.put(key, new RitualDefinition(key, enabled, kind, ingredients,
                        cooldown, attempts, window, duration, precision == null
                                ? Precision.REGION : precision,
                        failure, maxSessions,
                        java.util.Optional.ofNullable(knowledgeKey == null ? null
                                : knowledgeKey.trim())));
            }
        }
        return rituals;
    }

    private static List<Ingredient> loadIngredients(String path, ConfigurationSection def,
            List<String> issues) {
        List<Ingredient> ingredients = new ArrayList<>();
        List<Map<?, ?>> raw = def.getMapList("ingredients");
        if (raw.isEmpty()) {
            return ingredients;
        }
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> entry = raw.get(i);
            Object materialRaw = entry.get("material");
            Object amountRaw = entry.get("amount");
            Material material = materialRaw == null ? null
                    : Material.matchMaterial(materialRaw.toString().trim());
            // Registry-free validation (ADR-0015 serverless lesson).
            if (material == null || material == Material.AIR
                    || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
                issues.add(path + ".ingredients[" + i + "].material: must be a valid "
                        + "item material (got '" + materialRaw + "')");
                continue;
            }
            int amount = 1;
            try {
                amount = Integer.parseInt(String.valueOf(amountRaw));
            } catch (NumberFormatException e) {
                issues.add(path + ".ingredients[" + i + "].amount: must be an integer "
                        + "1.." + Ingredient.MAX_AMOUNT + " (got '" + amountRaw + "')");
                continue;
            }
            if (amount < 1 || amount > Ingredient.MAX_AMOUNT) {
                issues.add(path + ".ingredients[" + i + "].amount: must be an integer "
                        + "1.." + Ingredient.MAX_AMOUNT + " (got " + amount + ")");
                continue;
            }
            ingredients.add(new Ingredient(material, amount));
        }
        return ingredients;
    }

    // ------------------------------------------------------------------
    // Encounters
    // ------------------------------------------------------------------

    private static Map<String, EncounterDefinition> loadEncounters(YamlConfiguration yaml,
            List<String> issues) {
        Map<String, EncounterDefinition> encounters = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("rpg.encounters");
        if (section == null) {
            issues.add("rpg.encounters: missing section");
            return encounters;
        }
        for (String key : section.getKeys(false)) {
            String path = "rpg.encounters." + key;
            if (!KEY.matcher(key).matches()) {
                issues.add(path + ": invalid key (expected [a-z0-9_]{1,32})");
                continue;
            }
            ConfigurationSection def = section.getConfigurationSection(key);
            if (def == null) {
                issues.add(path + ": must be a section");
                continue;
            }

            boolean enabled = def.getBoolean("enabled", false);

            // Soul entity (controller surface): living types only — the
            // damage-protection path assumes a living entity. Registry-free
            // constant lookup + isAlive() (the ADR-0015 serverless lesson).
            String soulRaw = def.getString("soul-entity", "VILLAGER");
            org.bukkit.entity.EntityType soulEntity = parseSoulEntity(soulRaw);
            if (soulEntity == null) {
                issues.add(path + ".soul-entity: unknown entity type (got '"
                        + soulRaw + "')");
            } else if (!soulEntity.isAlive()) {
                issues.add(path + ".soul-entity: must be a living entity type (got '"
                        + soulRaw + "')");
            }

            List<String> worlds = def.getStringList("worlds");
            worlds = worlds.stream().map(String::trim).filter(s -> !s.isBlank()).toList();
            if (worlds.isEmpty()) {
                issues.add(path + ".worlds: at least one allowed world required "
                        + "(spawn rules are operator-calibrated world data)");
            } else if (worlds.stream().anyMatch(w -> w.length()
                    > EncounterDefinition.MAX_WORLD_NAME)) {
                issues.add(path + ".worlds: world names must be at most "
                        + EncounterDefinition.MAX_WORLD_NAME + " characters");
            }

            List<String> biomes = def.getStringList("biomes");
            biomes = biomes.stream().map(String::trim).filter(s -> !s.isBlank()).toList();
            if (biomes.stream().anyMatch(b -> b.length()
                    > EncounterDefinition.MAX_BIOME_NAME)) {
                issues.add(path + ".biomes: biome keys must be at most "
                        + EncounterDefinition.MAX_BIOME_NAME + " characters");
            }

            EncounterDefinition.TimeWindow window = EncounterDefinition.TimeWindow.parse(
                    def.getString("time-window", EncounterDefinition.TimeWindow.ANY.name()));
            if (window == null) {
                issues.add(path + ".time-window: must be one of DAWN, DAY, DUSK, NIGHT, "
                        + "ANY (got '" + def.getString("time-window") + "')");
            }

            int minPlayers = def.getInt("min-players", 1);
            int maxPlayers = def.getInt("max-players", 0);
            if (minPlayers < 1 || minPlayers > EncounterDefinition.MAX_PLAYERS) {
                issues.add(path + ".min-players: must be between 1 and "
                        + EncounterDefinition.MAX_PLAYERS + " (got " + minPlayers + ")");
            }
            if (maxPlayers < 0 || maxPlayers > EncounterDefinition.MAX_PLAYERS) {
                issues.add(path + ".max-players: must be between 0 (uncapped) and "
                        + EncounterDefinition.MAX_PLAYERS + " (got " + maxPlayers + ")");
            } else if (maxPlayers > 0 && maxPlayers < minPlayers) {
                issues.add(path + ".max-players: must be 0 (uncapped) or >= min-players "
                        + "(got " + maxPlayers + " < " + minPlayers + ")");
            }

            int maxConcurrent = def.getInt("max-concurrent", 1);
            if (maxConcurrent < 1 || maxConcurrent > EncounterDefinition.MAX_CONCURRENT) {
                issues.add(path + ".max-concurrent: must be between 1 and "
                        + EncounterDefinition.MAX_CONCURRENT + " (got " + maxConcurrent + ")");
            }

            long cooldown = def.getLong("cooldown-seconds", 86_400L);
            if (cooldown < 0 || cooldown > EncounterDefinition.MAX_SECONDS) {
                issues.add(path + ".cooldown-seconds: must be between 0 and "
                        + EncounterDefinition.MAX_SECONDS + " (got " + cooldown + ")");
            }

            long ttl = def.getLong("ttl-seconds", 1800L);
            if (ttl < EncounterDefinition.MIN_TTL_SECONDS
                    || ttl > EncounterDefinition.MAX_TTL_SECONDS) {
                issues.add(path + ".ttl-seconds: must be between "
                        + EncounterDefinition.MIN_TTL_SECONDS + " and "
                        + EncounterDefinition.MAX_TTL_SECONDS + " (got " + ttl + ")");
            }

            List<String> rewards = def.getStringList("rewards");
            rewards = rewards.stream().map(String::trim).filter(s -> !s.isBlank()).toList();
            if (rewards.isEmpty()) {
                issues.add(path + ".rewards: at least one discovery key required "
                        + "(helping the soul must pay out knowledge)");
            } else if (rewards.size() > EncounterDefinition.MAX_REWARDS) {
                issues.add(path + ".rewards: at most " + EncounterDefinition.MAX_REWARDS
                        + " discovery keys (got " + rewards.size() + ")");
            } else if (rewards.stream().anyMatch(r -> !KEY.matcher(r).matches())) {
                issues.add(path + ".rewards: every key must match [a-z0-9_]{1,32}");
            }

            if (issues.stream().noneMatch(i -> i.startsWith(path + ":")
                    || i.startsWith(path + "."))) {
                encounters.put(key, new EncounterDefinition(key, enabled,
                        soulEntity == null
                                ? org.bukkit.entity.EntityType.VILLAGER : soulEntity,
                        worlds, biomes,
                        window == null ? EncounterDefinition.TimeWindow.ANY : window,
                        minPlayers, maxPlayers, maxConcurrent, cooldown, ttl, rewards));
            }
        }
        return encounters;
    }

    /** Enum-constant lookup (registry-free); null on unknown input. */
    private static org.bukkit.entity.EntityType parseSoulEntity(String raw) {
        try {
            return org.bukkit.entity.EntityType.valueOf(
                    raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
