package net.zaminmc.rebellion.items;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** ItemsConfig parsing: modes, positions, registry, aggregation. */
final class ItemsConfigTest {

    private ItemsConfig.LoadResult load(YamlConfiguration yaml) {
        return ItemsConfig.load(yaml);
    }

    @Test
    void bundledDefaultConfigIsValid() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = ItemsConfigTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        var result = load(yaml);
        assertTrue(result.valid(), () -> result.issues().toString());
        assertEquals(ItemsConfig.AnnotationMode.CUSTOM_ONLY, result.config().annotationMode());
        assertEquals(ItemsConfig.LorePosition.BOTTOM, result.config().lorePosition());
        assertFalse(result.config().joinNormalization());
        assertFalse(result.config().pickupAnnotation());
        assertFalse(result.config().blockDropAnnotation());
        assertFalse(result.config().craftingOutputAnnotation());
        assertFalse(result.config().smithingOutputAnnotation());
        assertFalse(result.config().containerOpenAnnotation());
        assertFalse(result.config().allowConsoleActions());
        assertEquals(Map.of(
                "item_codex", "item_codex.yml",
                "talisman_guide", "talisman_guide.yml"), result.config().menuRegistry());
    }

    @Test
    void allModesParseCaseInsensitively() {
        for (String mode : new String[] {"custom-only", "managed-items", "all-items", "ALL-ITEMS"}) {
            YamlConfiguration yaml = base();
            yaml.set("annotation.mode", mode);
            var result = load(yaml);
            assertTrue(result.valid(), mode + " must parse: " + result.issues());
        }
    }

    @Test
    void flowSwitchesAreReadIndividually() {
        YamlConfiguration yaml = base();
        yaml.set("annotation.flows.block-drops", true);
        yaml.set("annotation.flows.container-open", true);
        var result = load(yaml);
        assertTrue(result.valid(), () -> result.issues().toString());
        assertTrue(result.config().blockDropAnnotation());
        assertTrue(result.config().containerOpenAnnotation());
        assertFalse(result.config().pickupAnnotation());
        assertFalse(result.config().craftingOutputAnnotation());
        assertFalse(result.config().smithingOutputAnnotation());
    }

    @Test
    void unknownModeIsPreciseIssue() {
        YamlConfiguration yaml = base();
        yaml.set("annotation.mode", "everything");
        var result = load(yaml);
        assertFalse(result.valid());
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.contains("annotation.mode")));
    }

    @Test
    void unknownLorePositionIsIssue() {
        YamlConfiguration yaml = base();
        yaml.set("annotation.lore-position", "middle");
        var result = load(yaml);
        assertFalse(result.valid());
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.contains("annotation.lore-position")));
    }

    @Test
    void wrongSchemaVersionIsRejected() {
        YamlConfiguration yaml = base();
        yaml.set("schema-version", 2);
        assertFalse(load(yaml).valid());
    }

    @Test
    void missingRegistrySectionIsIssue() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("annotation.mode", "custom-only");
        var result = load(yaml);
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("gui_menus")));
    }

    @Test
    void registryEntryWithoutFileIsIssue() {
        YamlConfiguration yaml = base();
        yaml.set("gui_menus.something", "");
        var result = load(yaml);
        assertFalse(result.valid());
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.contains("must be a section")));

        // A proper section that lacks a file reference is its own precise issue.
        YamlConfiguration yaml2 = base();
        yaml2.set("gui_menus.something.display_name", "x");
        var result2 = load(yaml2);
        assertFalse(result2.valid());
        assertTrue(result2.issues().stream()
                .anyMatch(i -> i.contains("missing file reference")));
    }

    private YamlConfiguration base() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        yaml.set("annotation.mode", "custom-only");
        yaml.set("gui_menus.item_codex.file", "item_codex.yml");
        return yaml;
    }
}
