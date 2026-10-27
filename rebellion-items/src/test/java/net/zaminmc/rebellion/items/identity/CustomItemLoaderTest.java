package net.zaminmc.rebellion.items.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** items.yml definitions: validation, id rules, tier/rarity axes. */
final class CustomItemLoaderTest {

    private static final Set<String> KNOWN = Set.of(
            "SUGAR", "AMETHYST_SHARD", "ECHO_SHARD", "RED_BANNER", "STONE");

    private CustomItemLoader loader() {
        return new CustomItemLoader(name -> KNOWN.contains(name));
    }

    private YamlConfiguration base() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("items.example.material", "STONE");
        yaml.set("items.example.rarity", "rare");
        return yaml;
    }

    @Test
    void bundledDefaultsAreValid() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = CustomItemLoaderTest.class.getResourceAsStream("/items.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        var loaded = loader().load(yaml);
        assertTrue(loaded.valid(), () -> loaded.issues().toString());
        assertEquals(4, loaded.items().size());

        // The talisman family demonstrates all three progression tiers with
        // rarity as the independent axis.
        assertEquals("normal", loaded.items().get("talisman_haste_normal").talismanTierKey());
        assertEquals("uncommon", loaded.items().get("talisman_haste_normal").rarityKey());
        assertEquals("artifact", loaded.items().get("talisman_haste_artifact").talismanTierKey());
        assertEquals("relic", loaded.items().get("talisman_haste_relic").talismanTierKey());
        assertEquals("epic", loaded.items().get("talisman_haste_relic").rarityKey());

        // Non-talisman items have no tier key at all (not a default tier).
        assertNull(loaded.items().get("banner_of_rebellion").talismanTierKey());

        // Unique instances tracked for all bundled examples.
        assertTrue(loaded.items().get("talisman_haste_normal").tracksInstances());
        assertTrue(loaded.items().get("banner_of_rebellion").tracksInstances());
    }

    @Test
    void validDefinitionParsesWithDefaults() {
        var loaded = loader().load(base());
        assertTrue(loaded.valid(), () -> loaded.issues().toString());
        var spec = loaded.items().get("example");
        assertEquals("STONE", spec.material());
        assertEquals("rare", spec.rarityKey());
        assertEquals("example", spec.displayName()); // defaults to the id
        assertTrue(spec.lore().isEmpty());
        assertFalse(spec.glint());
        assertFalse(spec.unique());
    }

    @Test
    void malformedIdIsRejected() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("items.Bad-Id", "x"); // section not string; invalid name either way
        assertFalse(loader().load(yaml).valid());

        YamlConfiguration yaml2 = new YamlConfiguration();
        yaml2.set("items.UPPER", "x");
        assertFalse(loader().load(yaml2).valid());
    }

    @Test
    void unknownMaterialIsPreciseIssue() {
        YamlConfiguration yaml = base();
        yaml.set("items.example.material", "PHILOSOPHERS_STONE");
        var loaded = loader().load(yaml);
        assertFalse(loaded.valid());
        assertTrue(loaded.issues().stream()
                .anyMatch(i -> i.contains("unknown material") && i.contains("PHILOSOPHERS_STONE")));
    }

    @Test
    void unknownRarityIsRejected() {
        YamlConfiguration yaml = base();
        yaml.set("items.example.rarity", "ABSURD");
        assertFalse(loader().load(yaml).valid());
    }

    @Test
    void unknownTalismanTierIsRejected() {
        YamlConfiguration yaml = base();
        yaml.set("items.example.talisman-tier", "GOD"); // tier axis ≠ rarity axis
        var loaded = loader().load(yaml);
        assertFalse(loaded.valid());
        assertTrue(loaded.issues().stream().anyMatch(i -> i.contains("talisman-tier")));
    }

    @Test
    void missingItemsSectionIsIssue() {
        var loaded = loader().load(new YamlConfiguration());
        assertFalse(loaded.valid());
        assertTrue(loaded.issues().get(0).contains("items: missing section"));
    }

    @Test
    void specIsImmutable() {
        var loaded = loader().load(base());
        assertThrows(UnsupportedOperationException.class,
                () -> loaded.items().put("another", null));
        assertThrows(UnsupportedOperationException.class,
                () -> loaded.items().get("example").lore().add("mutate"));
    }

    @Test
    void talismanTierEnumContract() {
        var tiers = net.zaminmc.rebellion.items.lore.TalismanTier.values();
        assertEquals(3, tiers.length);
        // The tier axis is exactly NORMAL/ARTIFACT/RELIC — never rarity values.
        for (var tier : tiers) {
            assertNotEquals(tier.key(), "very_special");
            assertTrue(net.zaminmc.rebellion.items.lore.TalismanTier.byKey(tier.key()).isPresent());
        }
        assertTrue(net.zaminmc.rebellion.items.lore.TalismanTier.byKey("relic").isPresent());
        assertTrue(net.zaminmc.rebellion.items.lore.TalismanTier.byKey("bogus").isEmpty());
        assertTrue(net.zaminmc.rebellion.items.lore.TalismanTier.byKey(null).isEmpty());
    }
}
