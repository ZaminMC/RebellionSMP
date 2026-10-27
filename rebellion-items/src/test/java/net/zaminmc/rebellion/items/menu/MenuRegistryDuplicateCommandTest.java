package net.zaminmc.rebellion.items.menu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MenuRegistry cross-menu constraints: a duplicate open_command across two
 * menu files rejects the whole candidate (atomic reload), while a registry
 * without conflicts loads clean.
 */
final class MenuRegistryDuplicateCommandTest {

    @TempDir
    Path menusDir;

    private static final MenuLoader LOADER = new MenuLoader(name -> true);

    private Path writeMenu(String file, String title, String openCommand) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("menu_title", title);
        yaml.set("size", 9);
        if (openCommand != null) {
            yaml.set("open_command", openCommand);
        }
        // Menus need at least one valid item (see MenuLoader rule: empty
        // items section is an issue). One filler button in slot 0.
        yaml.set("items.filler.material", "STONE");
        yaml.set("items.filler.slot", 0);
        Path p = menusDir.resolve(file);
        Files.writeString(p, yaml.saveToString());
        return p;
    }

    @Test
    void duplicateOpenCommandAcrossMenusRejectsCandidate() throws IOException {
        writeMenu("alpha.yml", "Alpha", "sharedcommand");
        writeMenu("beta.yml", "Beta", "sharedcommand");
        MenuRegistry registry = new MenuRegistry(menusDir, LOADER,
                Logger.getLogger("test"));

        var result = registry.loadCandidate(new LinkedHashMap<>(Map.of(
                "alpha", "alpha.yml", "beta", "beta.yml")));
        assertFalse(result.issues().isEmpty(), () -> result.issues().toString());
        assertTrue(result.issues().stream().anyMatch(i ->
                        i.contains("open_command '/sharedcommand'") && i.contains("unique")),
                () -> result.issues().toString());
        assertFalse(registry.activate(result), "candidate must not activate");
        assertTrue(registry.active().isEmpty());
    }

    @Test
    void distinctOpenCommandsLoadClean() throws IOException {
        writeMenu("alpha.yml", "Alpha", "alpha_menu");
        writeMenu("beta.yml", "Beta", "beta_menu");
        MenuRegistry registry = new MenuRegistry(menusDir, LOADER,
                Logger.getLogger("test"));

        var result = registry.loadCandidate(new LinkedHashMap<>(Map.of(
                "alpha", "alpha.yml", "beta", "beta.yml")));
        assertTrue(result.issues().isEmpty(), () -> result.issues().toString());
        assertTrue(registry.activate(result));
        assertTrue(registry.active().containsKey("alpha"));
        assertTrue(registry.active().containsKey("beta"));
    }
}
