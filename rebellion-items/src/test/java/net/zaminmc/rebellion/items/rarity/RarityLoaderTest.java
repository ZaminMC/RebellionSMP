package net.zaminmc.rebellion.items.rarity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Rarity resolution and rarities.yml validation. Server-free: the material
 * validator is injected (production uses the real registry; here a fixed set).
 */
final class RarityLoaderTest {

    private static final Set<String> KNOWN = Set.of(
            "DIRT", "DIAMOND_SWORD", "DRAGON_EGG", "NETHERITE_INGOT", "GLASS_BOTTLE",
            "ELYTRA", "TOTEM_OF_UNDYING", "NETHER_STAR", "ENCHANTED_GOLDEN_APPLE",
            "HEART_OF_THE_SEA", "BREEZE_ROD", "SHULKER_SHELL");

    private RarityLoader loader() {
        return new RarityLoader(name -> KNOWN.contains(name));
    }

    private YamlConfiguration minimal() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("rarities.COMMON.display-name", "Common");
        yaml.set("rarities.COMMON.color", "<gray>");
        return yaml;
    }

    @Test
    void fallbackIsCommonForUnmappedMaterials() {
        var loaded = loader().load(minimal());
        assertTrue(loaded.valid(), () -> loaded.issues().toString());
        assertEquals(Rarity.COMMON, loaded.registry().of("DIRT"));
        assertEquals(Rarity.COMMON, loaded.registry().of("GLASS_BOTTLE"));
        // Materials a future update introduces stay safe by the same rule.
        assertEquals(Rarity.COMMON, loaded.registry().of("SOME_FUTURE_BLOCK"));
        assertEquals(Rarity.COMMON, loaded.registry().of((String) null));
    }

    @Test
    void explicitOverridesResolve() {
        YamlConfiguration yaml = minimal();
        yaml.set("rarities.MYTHIC.display-name", "Mythic");
        yaml.set("rarities.MYTHIC.color", "<red>");
        yaml.set("materials.DIAMOND_SWORD", "EPIC");
        yaml.set("materials.DRAGON_EGG", "mythic"); // keys are case-insensitive
        var loaded = loader().load(yaml);
        assertTrue(loaded.valid(), () -> loaded.issues().toString());
        assertEquals(Rarity.EPIC, loaded.registry().of("DIAMOND_SWORD"));
        assertEquals(Rarity.MYTHIC, loaded.registry().of("DRAGON_EGG"));
        assertEquals(2, loaded.registry().overrides().size());
    }

    @Test
    void unknownMaterialNameIsAnIssue() {
        YamlConfiguration yaml = minimal();
        yaml.set("materials.NOT_A_MATERIAL", "RARE");
        var loaded = loader().load(yaml);
        assertFalse(loaded.valid());
        assertTrue(loaded.issues().stream().anyMatch(i -> i.contains("NOT_A_MATERIAL")
                && i.contains("unknown material")));
    }

    @Test
    void unknownRarityKeyIsAnIssueEverywhere() {
        YamlConfiguration yaml = minimal();
        yaml.set("rarities.ABSURD.display-name", "x");
        yaml.set("rarities.ABSURD.color", "<gray>");
        yaml.set("materials.DIRT", "ABSURD");
        var loaded = loader().load(yaml);
        assertFalse(loaded.valid());
        assertEquals(2, loaded.issues().size());
        assertTrue(loaded.issues().stream().allMatch(i -> i.contains("unknown rarity")));
    }

    @Test
    void missingRaritiesSectionIsAnIssue() {
        var loaded = loader().load(new YamlConfiguration());
        assertFalse(loaded.valid());
        assertTrue(loaded.issues().get(0).contains("missing 'rarities' section"));
    }

    @Test
    void negativeWeightAndMissingFieldsAreAggregated() {
        YamlConfiguration yaml = minimal();
        yaml.set("rarities.RARE.weight", -5); // weight issue
        yaml.set("rarities.EPIC.display-name", "Epic"); // but no color
        var loaded = loader().load(yaml);
        assertFalse(loaded.valid());
        assertTrue(loaded.issues().stream().anyMatch(i -> i.contains("weight")));
        assertTrue(loaded.issues().stream().anyMatch(i -> i.contains("missing color")));
    }

    @Test
    void duplicateMaterialMappingKeepsFirstAndReports() {
        YamlConfiguration yaml = minimal();
        yaml.set("materials.DIRT", "RARE");
        // Same key cannot be set twice through YamlConfiguration, so the
        // duplicate path is exercised through the builder directly.
        var builder = new RarityRegistry.Builder()
                .override("DIRT", Rarity.RARE);
        assertDoesNotThrow(() -> builder.override("DIRT", Rarity.EPIC));
        var registry = builder.build();
        assertEquals(1, registry.issues().size());
        assertEquals(Rarity.RARE, registry.of("DIRT"));
    }

    @Test
    void styleFallsBackSafelyForUnconfiguredRarity() {
        var loaded = loader().load(minimal());
        var style = loaded.registry().style(Rarity.GOD);
        assertEquals("<gray>", style.color());
        assertEquals(1, style.weight());
        assertFalse(style.glint());
    }

    @Test
    void bundledDefaultsAreValid() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = RarityLoaderTest.class.getResourceAsStream("/rarities.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        var loaded = loader().load(yaml);
        assertTrue(loaded.valid(), () -> loaded.issues().toString());
        assertEquals(Rarity.EPIC, loaded.registry().of("DIAMOND_SWORD"));
        assertEquals(Rarity.MYTHIC, loaded.registry().of("DRAGON_EGG"));
        assertEquals(Rarity.COMMON, loaded.registry().of("DIRT"));
        assertEquals(Rarity.COMMON, loaded.registry().of("GLASS_BOTTLE"));
        assertEquals("Mythic", loaded.registry().style(Rarity.MYTHIC).displayName());
        assertTrue(loaded.registry().style(Rarity.GOD).glint());
    }

    @Test
    void rarityEnumContract() {
        assertEquals(9, Rarity.values().length);
        assertEquals("very_special", Rarity.VERY_SPECIAL.key());
        assertTrue(Rarity.byKey("MYTHIC").isPresent());
        assertTrue(Rarity.byKey("mythic").isPresent());
        assertTrue(Rarity.byKey(" nonsense ").isEmpty());
        assertTrue(Rarity.byKey(null).isEmpty());
    }
}
