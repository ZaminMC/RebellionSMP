package net.zaminmc.rebellion.rpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Pins the RebellionRPG operator contract (ADR-0016 §5): the shipped
 * defaults, the rituals/encounters validation bounds, key grammar, the
 * KNOWLEDGE key rules, the disabled encounter default and the
 * precision-ordering contract the mask composition relies on.
 */
final class RpgConfigTest {

    /** A complete, valid rituals+encounters YAML mirroring the defaults. */
    private static YamlConfiguration validYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("rpg.rituals.tracking.enabled", true);
        yaml.set("rpg.rituals.tracking.kind", "TRACKING");
        yaml.set("rpg.rituals.tracking.ingredients", List.of(
                java.util.Map.of("material", "echo_shard", "amount", 4),
                java.util.Map.of("material", "diamond", "amount", 2)));
        yaml.set("rpg.rituals.tracking.cooldown-seconds", 3600);
        yaml.set("rpg.rituals.tracking.rate-limit.attempts", 3);
        yaml.set("rpg.rituals.tracking.rate-limit.window-seconds", 21600);
        yaml.set("rpg.rituals.tracking.duration-seconds", 1800);
        yaml.set("rpg.rituals.tracking.precision", "REGION");
        yaml.set("rpg.rituals.tracking.failure-chance", 0.25);
        yaml.set("rpg.rituals.tracking.max-sessions", 1);

        yaml.set("rpg.rituals.mask.enabled", true);
        yaml.set("rpg.rituals.mask.kind", "COUNTER_MASK");
        yaml.set("rpg.rituals.mask.ingredients",
                List.of(java.util.Map.of("material", "amethyst_shard", "amount", 2)));
        yaml.set("rpg.rituals.mask.cooldown-seconds", 7200);
        yaml.set("rpg.rituals.mask.rate-limit.attempts", 2);
        yaml.set("rpg.rituals.mask.rate-limit.window-seconds", 21600);
        yaml.set("rpg.rituals.mask.duration-seconds", 3600);

        yaml.set("rpg.rituals.soul_lore.enabled", true);
        yaml.set("rpg.rituals.soul_lore.kind", "KNOWLEDGE");
        yaml.set("rpg.rituals.soul_lore.ingredients", List.of(
                java.util.Map.of("material", "book", "amount", 1),
                java.util.Map.of("material", "lapis_lazuli", "amount", 8)));
        yaml.set("rpg.rituals.soul_lore.cooldown-seconds", 86400);
        yaml.set("rpg.rituals.soul_lore.rate-limit.attempts", 1);
        yaml.set("rpg.rituals.soul_lore.rate-limit.window-seconds", 86400);
        yaml.set("rpg.rituals.soul_lore.duration-seconds", 30);
        yaml.set("rpg.rituals.soul_lore.knowledge-key", "ritual_soul_communion");

        yaml.set("rpg.encounters.wandering_soul.enabled", false);
        yaml.set("rpg.encounters.wandering_soul.worlds", List.of("world"));
        yaml.set("rpg.encounters.wandering_soul.biomes", List.of());
        yaml.set("rpg.encounters.wandering_soul.time-window", "NIGHT");
        yaml.set("rpg.encounters.wandering_soul.min-players", 1);
        yaml.set("rpg.encounters.wandering_soul.max-players", 0);
        yaml.set("rpg.encounters.wandering_soul.max-concurrent", 1);
        yaml.set("rpg.encounters.wandering_soul.cooldown-seconds", 86400);
        yaml.set("rpg.encounters.wandering_soul.ttl-seconds", 1800);
        yaml.set("rpg.encounters.wandering_soul.rewards",
                List.of("ritual_soul_communion"));
        return yaml;
    }

    @Test
    void validYamlLoadsAndMirrorsDefaults() {
        RpgConfig.LoadResult result = RpgConfig.loadResult(validYaml());
        assertTrue(result.valid(), () -> result.issues().toString());
        RpgConfig loaded = result.config();
        RpgConfig defaults = RpgConfig.defaults();
        assertEquals(defaults.rituals(), loaded.rituals(),
                "the shipped defaults must mirror the documented YAML");
        assertEquals(defaults.encounters(), loaded.encounters());
        assertEquals(3, loaded.rituals().size());
        assertEquals(1, loaded.encounters().size());
    }

    @Test
    void missingSectionsAreReported() {
        RpgConfig.LoadResult result = RpgConfig.loadResult(new YamlConfiguration());
        assertFalse(result.valid());
        assertEquals(2, result.issues().size());
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("rituals")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("encounters")));
    }

    @Test
    void ritualKeyGrammarIsEnforced() {
        YamlConfiguration yaml = validYaml();
        yaml.set("rpg.rituals.Bad-Key.kind", "TRACKING");
        RpgConfig.LoadResult result = RpgConfig.loadResult(yaml);
        assertTrue(result.issues().stream().anyMatch(i ->
                i.contains("Bad-Key")));
    }

    @Test
    void unknownRitualKindIsRefused() {
        YamlConfiguration yaml = validYaml();
        yaml.set("rpg.rituals.tracking.kind", "SUMMON");
        RpgConfig.LoadResult result = RpgConfig.loadResult(yaml);
        assertTrue(result.issues().stream().anyMatch(i ->
                i.startsWith("rpg.rituals.tracking.kind")));
    }

    @Test
    void ingredientValidationBounds() {
        YamlConfiguration badMaterial = validYaml();
        badMaterial.set("rpg.rituals.tracking.ingredients", List.of(
                java.util.Map.of("material", "not_a_thing", "amount", 4)));
        assertTrue(RpgConfig.loadResult(badMaterial).issues().stream()
                .anyMatch(i -> i.contains("ingredients[0].material")));

        YamlConfiguration zeroAmount = validYaml();
        zeroAmount.set("rpg.rituals.tracking.ingredients", List.of(
                java.util.Map.of("material", "diamond", "amount", 0)));
        assertTrue(RpgConfig.loadResult(zeroAmount).issues().stream()
                .anyMatch(i -> i.contains("ingredients[0].amount")));

        YamlConfiguration overAmount = validYaml();
        overAmount.set("rpg.rituals.tracking.ingredients", List.of(
                java.util.Map.of("material", "diamond", "amount", 65)));
        assertTrue(RpgConfig.loadResult(overAmount).issues().stream()
                .anyMatch(i -> i.contains("ingredients[0].amount")));

        YamlConfiguration air = validYaml();
        air.set("rpg.rituals.tracking.ingredients", List.of(
                java.util.Map.of("material", "air", "amount", 1)));
        assertTrue(RpgConfig.loadResult(air).issues().stream()
                .anyMatch(i -> i.contains("ingredients[0].material")));

        YamlConfiguration empty = validYaml();
        empty.set("rpg.rituals.tracking.ingredients", List.of());
        assertTrue(RpgConfig.loadResult(empty).issues().stream()
                .anyMatch(i -> i.endsWith("at least one ingredient required")));
    }

    @Test
    void failureChanceMustStayBelowCertainty() {
        YamlConfiguration certain = validYaml();
        certain.set("rpg.rituals.tracking.failure-chance", 1.0);
        assertTrue(RpgConfig.loadResult(certain).issues().stream()
                .anyMatch(i -> i.contains("failure-chance")));

        YamlConfiguration atBound = validYaml();
        atBound.set("rpg.rituals.tracking.failure-chance", 0.95);
        assertTrue(RpgConfig.loadResult(atBound).issues().stream()
                .anyMatch(i -> i.contains("failure-chance")));

        YamlConfiguration highButLegal = validYaml();
        highButLegal.set("rpg.rituals.tracking.failure-chance", 0.9);
        assertTrue(RpgConfig.loadResult(highButLegal).valid(),
                () -> RpgConfig.loadResult(highButLegal).issues().toString());
    }

    @Test
    void precisionAndBoundsValidation() {
        YamlConfiguration badPrecision = validYaml();
        badPrecision.set("rpg.rituals.tracking.precision", "EXACT");
        assertTrue(RpgConfig.loadResult(badPrecision).issues().stream()
                .anyMatch(i -> i.contains("precision")));

        YamlConfiguration shortDuration = validYaml();
        shortDuration.set("rpg.rituals.tracking.duration-seconds", 10);
        assertTrue(RpgConfig.loadResult(shortDuration).issues().stream()
                .anyMatch(i -> i.contains("duration-seconds")));

        YamlConfiguration badRate = validYaml();
        badRate.set("rpg.rituals.tracking.rate-limit.attempts", 0);
        assertTrue(RpgConfig.loadResult(badRate).issues().stream()
                .anyMatch(i -> i.contains("rate-limit.attempts")));

        YamlConfiguration badWindow = validYaml();
        badWindow.set("rpg.rituals.tracking.rate-limit.window-seconds", 0);
        assertTrue(RpgConfig.loadResult(badWindow).issues().stream()
                .anyMatch(i -> i.contains("rate-limit.window-seconds")));

        YamlConfiguration badCooldown = validYaml();
        badCooldown.set("rpg.rituals.tracking.cooldown-seconds", -1);
        assertTrue(RpgConfig.loadResult(badCooldown).issues().stream()
                .anyMatch(i -> i.contains("cooldown-seconds")));

        YamlConfiguration badSessions = validYaml();
        badSessions.set("rpg.rituals.tracking.max-sessions", 11);
        assertTrue(RpgConfig.loadResult(badSessions).issues().stream()
                .anyMatch(i -> i.contains("max-sessions")));
    }

    @Test
    void knowledgeKeyRules() {
        YamlConfiguration missing = validYaml();
        yamlSet(missing, "rpg.rituals.soul_lore.knowledge-key", null);
        assertTrue(RpgConfig.loadResult(missing).issues().stream()
                .anyMatch(i -> i.contains("knowledge-key")));

        YamlConfiguration badKey = validYaml();
        badKey.set("rpg.rituals.soul_lore.knowledge-key", "Not A Key");
        assertTrue(RpgConfig.loadResult(badKey).issues().stream()
                .anyMatch(i -> i.contains("knowledge-key")));

        YamlConfiguration wrongFamily = validYaml();
        wrongFamily.set("rpg.rituals.tracking.knowledge-key", "something");
        assertTrue(RpgConfig.loadResult(wrongFamily).issues().stream()
                .anyMatch(i -> i.contains("knowledge-key")
                        && i.contains("KNOWLEDGE")));
    }

    private static void yamlSet(YamlConfiguration yaml, String path, Object value) {
        yaml.set(path, value);
    }

    @Test
    void encounterValidationBounds() {
        YamlConfiguration noWorlds = validYaml();
        noWorlds.set("rpg.encounters.wandering_soul.worlds", List.of());
        assertTrue(RpgConfig.loadResult(noWorlds).issues().stream()
                .anyMatch(i -> i.contains("worlds")));

        YamlConfiguration noRewards = validYaml();
        noRewards.set("rpg.encounters.wandering_soul.rewards", List.of());
        assertTrue(RpgConfig.loadResult(noRewards).issues().stream()
                .anyMatch(i -> i.contains("rewards")));

        YamlConfiguration badRewardKey = validYaml();
        badRewardKey.set("rpg.encounters.wandering_soul.rewards",
                List.of("Not A Key"));
        assertTrue(RpgConfig.loadResult(badRewardKey).issues().stream()
                .anyMatch(i -> i.contains("rewards")));

        YamlConfiguration invertedPlayers = validYaml();
        invertedPlayers.set("rpg.encounters.wandering_soul.min-players", 5);
        invertedPlayers.set("rpg.encounters.wandering_soul.max-players", 2);
        assertTrue(RpgConfig.loadResult(invertedPlayers).issues().stream()
                .anyMatch(i -> i.contains("max-players")));

        YamlConfiguration shortTtl = validYaml();
        shortTtl.set("rpg.encounters.wandering_soul.ttl-seconds", 10);
        assertTrue(RpgConfig.loadResult(shortTtl).issues().stream()
                .anyMatch(i -> i.contains("ttl-seconds")));

        YamlConfiguration badWindow = validYaml();
        badWindow.set("rpg.encounters.wandering_soul.time-window", "MIDNIGHT");
        assertTrue(RpgConfig.loadResult(badWindow).issues().stream()
                .anyMatch(i -> i.contains("time-window")));

        YamlConfiguration badKey = validYaml();
        badKey.set("rpg.encounters.Wandering-Soul.enabled", false);
        assertTrue(RpgConfig.loadResult(badKey).issues().stream()
                .anyMatch(i -> i.contains("Wandering-Soul")));
    }

    @Test
    void shippedEncounterIsDisabledUntilCalibrated() {
        // The wards calibrated-scale precedent: nothing auto-spawns from a
        // stock install; the operator calibrates spawn rules on import.
        assertFalse(RpgConfig.defaults().encounter("wandering_soul").enabled());
        assertTrue(RpgConfig.defaults().ritual("tracking").enabled());
        assertTrue(RpgConfig.defaults().ritual("mask").enabled());
    }

    @Test
    void digestIsDeterministicRefundEvidence() {
        assertEquals("echo_shard:4,diamond:2",
                RpgConfig.defaults().ritual("tracking").digest());
        assertEquals("amethyst_shard:2",
                RpgConfig.defaults().ritual("mask").digest());
    }

    @Test
    void precisionOrderingIsTheMaskContract() {
        assertEquals(Optional.of(Precision.REGION), Precision.COORDINATE.degraded());
        assertEquals(Optional.of(Precision.DIMENSION), Precision.REGION.degraded());
        assertTrue(Precision.DIMENSION.degraded().isEmpty());
        assertEquals(Precision.COORDINATE, Precision.parse("coordinate"));
        assertEquals(Precision.REGION, Precision.parse(" REGION "));
        assertEquals(null, Precision.parse("EXACT"));
    }

    @Test
    void worldMatchIsCaseInsensitive() {
        EncounterDefinition definition = RpgConfig.defaults().encounter(
                "wandering_soul");
        assertTrue(definition.allowsWorld("World"));
        assertFalse(definition.allowsWorld("world_nether"));
    }

    @Test
    void everyConfiguredRitualCarriesItsKindContract() {
        RitualDefinition tracking = RpgConfig.defaults().ritual("tracking");
        assertEquals(RitualKind.TRACKING, tracking.kind());
        assertEquals(Precision.REGION, tracking.precision());

        RitualDefinition mask = RpgConfig.defaults().ritual("mask");
        assertEquals(RitualKind.COUNTER_MASK, mask.kind());

        RitualDefinition lore = RpgConfig.defaults().ritual("soul_lore");
        assertEquals(RitualKind.KNOWLEDGE, lore.kind());
        assertEquals(Optional.of("ritual_soul_communion"), lore.knowledgeKey());
        assertNotNull(lore.ingredients());
        assertFalse(lore.ingredients().isEmpty());
    }
}
