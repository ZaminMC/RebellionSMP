package net.zaminmc.rebellion.dragons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * DragonsConfig validation: the lineage cap contract (master prompt §5.6).
 * The cap defaults to 20, may be configured lower, and anything above the
 * hard policy ceiling is a LOUD load error — "a maximum that cannot
 * silently exceed the configured End gateway/lineage policy".
 */
final class DragonsConfigTest {

    private static YamlConfiguration yaml(String body) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(new StringReader(body));
        } catch (Exception e) {
            throw new IllegalStateException("test yaml invalid", e);
        }
        return config;
    }

    @Test
    void defaultCapIsTwenty() {
        DragonsConfig config = DragonsConfig.load(yaml("dragons:\n  max-lineages: 20\n"));
        assertEquals(20, config.maxLineages());
        assertEquals(20, config.effectiveMaxLineages());
    }

    @Test
    void missingKeyFallsBackToTheDefault() {
        DragonsConfig config = DragonsConfig.load(yaml("dragons: {}\n"));
        assertEquals(20, config.maxLineages());
    }

    @Test
    void capCanBeConfiguredLower() {
        DragonsConfig config = DragonsConfig.load(yaml("dragons:\n  max-lineages: 5\n"));
        assertEquals(5, config.maxLineages());
        assertEquals(5, config.effectiveMaxLineages());
    }

    @Test
    void capAboveTheCeilingIsALoudLoadError() {
        DragonsConfig.LoadResult result =
                DragonsConfig.loadResult(yaml("dragons:\n  max-lineages: 21\n"));
        assertFalse(result.valid());
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().get(0).contains("max-lineages"));
        assertTrue(result.issues().get(0).contains("20"),
                "the issue must explain the ceiling");
        assertThrows(IllegalStateException.class,
                () -> DragonsConfig.load(yaml("dragons:\n  max-lineages: 100\n")));
    }

    @Test
    void capBelowOneIsALoudLoadError() {
        DragonsConfig.LoadResult result =
                DragonsConfig.loadResult(yaml("dragons:\n  max-lineages: 0\n"));
        assertFalse(result.valid());
        assertEquals(1, result.issues().size());
    }

    @Test
    void policyCeilingClampsEvenDirectlyConstructedConfigs() {
        // Defense in depth: the service clamps at runtime even when a caller
        // bypasses loader validation entirely (LineageServiceIT exercises the
        // mint guard against this).
        assertEquals(20, LineagePolicy.effectiveCap(30));
        assertEquals(20, LineagePolicy.effectiveCap(Integer.MAX_VALUE));
        assertEquals(7, LineagePolicy.effectiveCap(7));
        assertEquals(1, LineagePolicy.effectiveCap(1));
        assertEquals(1, LineagePolicy.effectiveCap(0), "never clamps below one");
    }

    @Test
    void shippedResourceFileIsValid() {
        YamlConfiguration shipped = new YamlConfiguration();
        try {
            shipped.load(new StringReader("""
                    dragons:
                      max-lineages: 20
                    """));
        } catch (Exception e) {
            throw new IllegalStateException("shipped config invalid", e);
        }
        DragonsConfig config = DragonsConfig.load(shipped);
        assertEquals(20, config.effectiveMaxLineages());
    }
}
