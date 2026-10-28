package net.zaminmc.rebellion.alchemy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.potion.PotionType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Pins the RebellionAlchemy operator contract (ADR-0019 §5): the shipped
 * defaults, the four definition families' validation bounds, key grammar,
 * the POWER/EFFICIENCY shape separation, prerequisite reference + cycle
 * rejection, cross-family checks and the auto-consumption policy gates.
 */
final class AlchemyConfigTest {

    /** A complete, valid four-family YAML mirroring the defaults. */
    private static YamlConfiguration validYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("alchemy.brewing.max-level", 30);
        yaml.set("alchemy.brewing.level-xp-base", 100);
        yaml.set("alchemy.brewing.level-xp-growth", 25);
        yaml.set("alchemy.brewing.xp-per-brew", 10);

        yaml.set("alchemy.unlocks.potent_swiftness.kind", "POWER");
        yaml.set("alchemy.unlocks.potent_swiftness.enabled", true);
        yaml.set("alchemy.unlocks.potent_swiftness.required-level", 5);
        yaml.set("alchemy.unlocks.potent_swiftness.prerequisites", List.of());
        yaml.set("alchemy.unlocks.potent_swiftness.cost",
                List.of(Map.of("material", "amethyst_shard", "amount", 8)));
        yaml.set("alchemy.unlocks.potent_swiftness.potion-type", "SWIFTNESS");
        yaml.set("alchemy.unlocks.potent_swiftness.amplifier-bonus", 1);
        yaml.set("alchemy.unlocks.potent_swiftness.duration-multiplier", 1.5);

        yaml.set("alchemy.unlocks.frugal_brewing.kind", "EFFICIENCY");
        yaml.set("alchemy.unlocks.frugal_brewing.enabled", true);
        yaml.set("alchemy.unlocks.frugal_brewing.required-level", 3);
        yaml.set("alchemy.unlocks.frugal_brewing.prerequisites", List.of());
        yaml.set("alchemy.unlocks.frugal_brewing.cost",
                List.of(Map.of("material", "gold_ingot", "amount", 4)));
        yaml.set("alchemy.unlocks.frugal_brewing.cost-reduction", 0.10);
        yaml.set("alchemy.unlocks.frugal_brewing.bag-capacity-bonus", 0);
        yaml.set("alchemy.unlocks.frugal_brewing.auto-cooldown-reduction", 15);

        yaml.set("alchemy.bags.satchel.enabled", true);
        yaml.set("alchemy.bags.satchel.capacity", 9);
        yaml.set("alchemy.bags.satchel.material", "leather");
        yaml.set("alchemy.bags.satchel.required-unlock", "");
        yaml.set("alchemy.bags.satchel_large.enabled", true);
        yaml.set("alchemy.bags.satchel_large.capacity", 18);
        yaml.set("alchemy.bags.satchel_large.material", "leather");
        yaml.set("alchemy.bags.satchel_large.required-unlock", "frugal_brewing");

        yaml.set("alchemy.auto-rules.max-rules-per-player", 5);
        yaml.set("alchemy.auto-rules.min-cooldown-seconds", 60);
        yaml.set("alchemy.auto-rules.min-priority", 1);
        yaml.set("alchemy.auto-rules.max-priority", 100);
        yaml.set("alchemy.auto-rules.triggers.HEALTH_BELOW.enabled", true);
        yaml.set("alchemy.auto-rules.triggers.HEALTH_BELOW.min-threshold", 1);
        yaml.set("alchemy.auto-rules.triggers.HEALTH_BELOW.max-threshold", 99);
        yaml.set("alchemy.auto-rules.triggers.BURNING.enabled", true);
        yaml.set("alchemy.auto-rules.triggers.NETHER_ENTRY.enabled", true);
        yaml.set("alchemy.auto-rules.triggers.FALLING.enabled", false);
        yaml.set("alchemy.auto-rules.allowed-potion-types",
                List.of("HEALING", "REGENERATION", "FIRE_RESISTANCE", "SWIFTNESS",
                        "STRENGTH", "NIGHT_VISION", "WATER_BREATHING", "SLOW_FALLING"));
        return yaml;
    }

    @Test
    void validYamlLoadsAndMirrorsDefaults() {
        AlchemyConfig.LoadResult result = AlchemyConfig.loadResult(validYaml());
        assertTrue(result.valid(), () -> result.issues().toString());
        AlchemyConfig loaded = result.config();
        AlchemyConfig defaults = AlchemyConfig.defaults();
        assertEquals(defaults.brewing(), loaded.brewing(),
                "the shipped defaults must mirror the documented YAML");
        assertEquals(defaults.unlocks(), loaded.unlocks());
        assertEquals(defaults.bags(), loaded.bags());
        assertEquals(defaults.autoPolicy(), loaded.autoPolicy());
        assertEquals(2, loaded.unlocks().size());
        assertEquals(2, loaded.bags().size());
    }

    @Test
    void missingSectionsAreReported() {
        AlchemyConfig.LoadResult result = AlchemyConfig.loadResult(
                new YamlConfiguration());
        assertFalse(result.valid());
        assertEquals(4, result.issues().size());
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("brewing")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("unlocks")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("bags")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("auto-rules")));
    }

    @Test
    void unlockKeyGrammarIsEnforced() {
        YamlConfiguration yaml = validYaml();
        yaml.set("alchemy.unlocks.Bad-Key.kind", "POWER");
        assertTrue(AlchemyConfig.loadResult(yaml).issues().stream()
                .anyMatch(i -> i.contains("Bad-Key")));
    }

    @Test
    void brewingBoundsAreEnforced() {
        YamlConfiguration yaml = validYaml();
        yaml.set("alchemy.brewing.max-level", 0);
        assertTrue(AlchemyConfig.loadResult(yaml).issues().stream()
                .anyMatch(i -> i.contains("max-level")));

        YamlConfiguration growth = validYaml();
        growth.set("alchemy.brewing.level-xp-growth", -1);
        assertTrue(AlchemyConfig.loadResult(growth).issues().stream()
                .anyMatch(i -> i.contains("level-xp-growth")));
    }

    @Test
    void unknownUnlockKindIsRefused() {
        YamlConfiguration yaml = validYaml();
        yaml.set("alchemy.unlocks.potent_swiftness.kind", "MYSTIC");
        assertTrue(AlchemyConfig.loadResult(yaml).issues().stream()
                .anyMatch(i -> i.startsWith("alchemy.unlocks.potent_swiftness.kind")));
    }

    @Test
    void powerUnlockCannotCarryEfficiencyFields() {
        YamlConfiguration yaml = validYaml();
        yaml.set("alchemy.unlocks.potent_swiftness.cost-reduction", 0.5);
        List<String> issues = AlchemyConfig.loadResult(yaml).issues();
        assertTrue(issues.stream().anyMatch(i -> i.contains("disguised power")),
                () -> issues.toString());
    }

    @Test
    void efficiencyUnlockCannotCarryPowerFields() {
        YamlConfiguration yaml = validYaml();
        yaml.set("alchemy.unlocks.frugal_brewing.amplifier-bonus", 2);
        List<String> issues = AlchemyConfig.loadResult(yaml).issues();
        assertTrue(issues.stream().anyMatch(i -> i.contains("combat strength")),
                () -> issues.toString());
    }

    @Test
    void fullCostReductionIsRejected() {
        YamlConfiguration yaml = validYaml();
        yaml.set("alchemy.unlocks.frugal_brewing.cost-reduction", 1.0);
        assertTrue(AlchemyConfig.loadResult(yaml).issues().stream()
                .anyMatch(i -> i.contains("mints free potions")));
    }

    @Test
    void powerGrantBoundsAreEnforced() {
        YamlConfiguration amplifier = validYaml();
        amplifier.set("alchemy.unlocks.potent_swiftness.amplifier-bonus", 5);
        assertTrue(AlchemyConfig.loadResult(amplifier).issues().stream()
                .anyMatch(i -> i.contains("amplifier-bonus")));

        YamlConfiguration duration = validYaml();
        duration.set("alchemy.unlocks.potent_swiftness.duration-multiplier", 0.5);
        assertTrue(AlchemyConfig.loadResult(duration).issues().stream()
                .anyMatch(i -> i.contains("duration-multiplier")));

        YamlConfiguration potion = validYaml();
        potion.set("alchemy.unlocks.potent_swiftness.potion-type", "NOT_A_POTION");
        assertTrue(AlchemyConfig.loadResult(potion).issues().stream()
                .anyMatch(i -> i.contains("potion-type")));
    }

    @Test
    void requiredLevelIsCheckedAgainstTheCurveMax() {
        YamlConfiguration yaml = validYaml();
        yaml.set("alchemy.unlocks.potent_swiftness.required-level", 31);
        assertTrue(AlchemyConfig.loadResult(yaml).issues().stream()
                .anyMatch(i -> i.contains("required-level")));
    }

    @Test
    void costValidationBounds() {
        YamlConfiguration badMaterial = validYaml();
        badMaterial.set("alchemy.unlocks.potent_swiftness.cost",
                List.of(Map.of("material", "not_a_thing", "amount", 4)));
        assertTrue(AlchemyConfig.loadResult(badMaterial).issues().stream()
                .anyMatch(i -> i.contains("cost[0].material")));

        YamlConfiguration zeroAmount = validYaml();
        zeroAmount.set("alchemy.unlocks.potent_swiftness.cost",
                List.of(Map.of("material", "diamond", "amount", 0)));
        assertTrue(AlchemyConfig.loadResult(zeroAmount).issues().stream()
                .anyMatch(i -> i.contains("cost[0].amount")));

        YamlConfiguration empty = validYaml();
        empty.set("alchemy.unlocks.potent_swiftness.cost", List.of());
        assertTrue(AlchemyConfig.loadResult(empty).issues().stream()
                .anyMatch(i -> i.contains("at least one cost item")));
    }

    @Test
    void unknownPrerequisiteReferenceIsRejected() {
        YamlConfiguration yaml = validYaml();
        yaml.set("alchemy.unlocks.potent_swiftness.prerequisites",
                List.of("nonexistent_unlock"));
        assertTrue(AlchemyConfig.loadResult(yaml).issues().stream()
                .anyMatch(i -> i.contains("unknown unlock 'nonexistent_unlock'")));
    }

    @Test
    void prerequisiteCyclesAreRejected() {
        YamlConfiguration yaml = validYaml();
        yaml.set("alchemy.unlocks.potent_swiftness.prerequisites",
                List.of("frugal_brewing"));
        yaml.set("alchemy.unlocks.frugal_brewing.prerequisites",
                List.of("potent_swiftness"));
        List<String> issues = AlchemyConfig.loadResult(yaml).issues();
        assertTrue(issues.stream().anyMatch(i -> i.contains("cycle")),
                () -> issues.toString());
    }

    @Test
    void selfPrerequisiteIsACycle() {
        YamlConfiguration yaml = validYaml();
        yaml.set("alchemy.unlocks.frugal_brewing.prerequisites",
                List.of("frugal_brewing"));
        assertTrue(AlchemyConfig.loadResult(yaml).issues().stream()
                .anyMatch(i -> i.contains("cycle")));
    }

    @Test
    void orderedPrerequisitesResolveWithoutFalseCycles() {
        YamlConfiguration yaml = validYaml();
        yaml.set("alchemy.unlocks.potent_swiftness.prerequisites",
                List.of("frugal_brewing"));
        assertTrue(AlchemyConfig.loadResult(yaml).valid(),
                "a real DAG must load");
    }

    @Test
    void bagBoundsAndReferences() {
        YamlConfiguration capacity = validYaml();
        capacity.set("alchemy.bags.satchel.capacity", 55);
        assertTrue(AlchemyConfig.loadResult(capacity).issues().stream()
                .anyMatch(i -> i.contains("capacity")));

        YamlConfiguration material = validYaml();
        material.set("alchemy.bags.satchel.material", "not_a_thing");
        assertTrue(AlchemyConfig.loadResult(material).issues().stream()
                .anyMatch(i -> i.contains("material")));

        YamlConfiguration reference = validYaml();
        reference.set("alchemy.bags.satchel_large.required-unlock", "unknown_unlock");
        assertTrue(AlchemyConfig.loadResult(reference).issues().stream()
                .anyMatch(i -> i.contains("unknown unlock 'unknown_unlock'")));
    }

    @Test
    void policyBoundsAreEnforced() {
        YamlConfiguration cooldown = validYaml();
        cooldown.set("alchemy.auto-rules.min-cooldown-seconds", 0);
        assertTrue(AlchemyConfig.loadResult(cooldown).issues().stream()
                .anyMatch(i -> i.contains("min-cooldown-seconds")));

        YamlConfiguration maxRules = validYaml();
        maxRules.set("alchemy.auto-rules.max-rules-per-player", 11);
        assertTrue(AlchemyConfig.loadResult(maxRules).issues().stream()
                .anyMatch(i -> i.contains("max-rules-per-player")));

        YamlConfiguration priorities = validYaml();
        priorities.set("alchemy.auto-rules.max-priority", 0);
        assertTrue(AlchemyConfig.loadResult(priorities).issues().stream()
                .anyMatch(i -> i.contains("max-priority")));
    }

    @Test
    void everyTriggerMustBeExplicitlyDeclared() {
        YamlConfiguration yaml = validYaml();
        yaml.set("alchemy.auto-rules.triggers.BURNING", null);
        List<String> issues = AlchemyConfig.loadResult(yaml).issues();
        assertTrue(issues.stream().anyMatch(i ->
                        i.contains("BURNING: missing")),
                () -> issues.toString());

        YamlConfiguration unknown = validYaml();
        unknown.set("alchemy.auto-rules.triggers.SUFFOCATION.enabled", true);
        assertTrue(AlchemyConfig.loadResult(unknown).issues().stream()
                .anyMatch(i -> i.contains("SUFFOCATION")));
    }

    @Test
    void healthThresholdBoundsAreEnforced() {
        YamlConfiguration min = validYaml();
        min.set("alchemy.auto-rules.triggers.HEALTH_BELOW.min-threshold", 0);
        assertTrue(AlchemyConfig.loadResult(min).issues().stream()
                .anyMatch(i -> i.contains("min-threshold")));

        YamlConfiguration inverted = validYaml();
        inverted.set("alchemy.auto-rules.triggers.HEALTH_BELOW.min-threshold", 50);
        inverted.set("alchemy.auto-rules.triggers.HEALTH_BELOW.max-threshold", 10);
        assertTrue(AlchemyConfig.loadResult(inverted).issues().stream()
                .anyMatch(i -> i.contains("max-threshold")));
    }

    @Test
    void potionWhitelistIsEnumValidated() {
        YamlConfiguration unknown = validYaml();
        unknown.set("alchemy.auto-rules.allowed-potion-types", List.of("HEALING",
                "DRAGON_BREATH"));
        assertTrue(AlchemyConfig.loadResult(unknown).issues().stream()
                .anyMatch(i -> i.contains("DRAGON_BREATH")));

        YamlConfiguration empty = validYaml();
        empty.set("alchemy.auto-rules.allowed-potion-types", List.of());
        assertTrue(AlchemyConfig.loadResult(empty).issues().stream()
                .anyMatch(i -> i.contains("at least one potion")));
    }

    @Test
    void defaultsAreConsistent() {
        AlchemyConfig defaults = AlchemyConfig.defaults();
        UnlockDefinition power = defaults.unlock("potent_swiftness");
        assertEquals(UnlockKind.POWER, power.kind());
        assertEquals(5, power.requiredLevel());
        assertEquals(PotionType.SWIFTNESS, power.potionType().orElseThrow());
        assertEquals(Material.AMETHYST_SHARD, power.cost().get(0).material());

        UnlockDefinition efficiency = defaults.unlock("frugal_brewing");
        assertEquals(UnlockKind.EFFICIENCY, efficiency.kind());
        assertEquals(0.10, efficiency.costReduction(), 1e-9);
        assertTrue(efficiency.potionType().isEmpty());

        BagDefinition large = defaults.bag("satchel_large");
        assertEquals(18, large.capacity());
        assertEquals("frugal_brewing", large.requiredUnlock().orElseThrow());

        AutoPolicy policy = defaults.autoPolicy();
        assertTrue(policy.triggerEnabled(AutoTrigger.HEALTH_BELOW));
        assertFalse(policy.triggerEnabled(AutoTrigger.FALLING),
                "FALLING ships disabled - a deliberate opt-in feel decision");
        assertFalse(policy.potionAllowed(PotionType.POISON),
                "POISON deliberately absent from the shipped whitelist");
        assertTrue(policy.potionAllowed(PotionType.HEALING));
        assertEquals(60, policy.minCooldownSeconds());
        assertEquals(5, policy.maxRulesPerPlayer());
    }

    @Test
    void issuePathsCarryTheirFullConfigPath() {
        YamlConfiguration yaml = validYaml();
        yaml.set("alchemy.unlocks.potent_swiftness.amplifier-bonus", 9);
        assertTrue(AlchemyConfig.loadResult(yaml).issues().stream()
                .anyMatch(i -> i.startsWith("alchemy.unlocks.potent_swiftness"
                        + ".amplifier-bonus")),
                "operators must be able to locate the offending entry");
    }
}
