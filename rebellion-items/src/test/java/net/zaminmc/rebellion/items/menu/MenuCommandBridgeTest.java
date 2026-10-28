package net.zaminmc.rebellion.items.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/**
 * Menu command bridge: registration, duplicate refusal, collision refusal,
 * reload resync and clean unregister — all against an in-memory CommandMap.
 */
final class MenuCommandBridgeTest {

    /** Minimal CommandMap mimic of SimpleCommandMap's registration behavior. */
    private static final class FakeCommandMap implements CommandMap {
        final Map<String, Command> known = new LinkedHashMap<>();

        @Override
        public boolean register(String label, String fallbackPrefix, Command command) {
            if (known.containsKey(label)) {
                return false; // SimpleCommandMap refuses/aliases; we refuse to keep it strict
            }
            known.put(label, command);
            known.put(fallbackPrefix + ":" + label, command);
            command.register(this);
            return true;
        }

        @Override
        public boolean register(String fallbackPrefix, Command command) {
            return register(command.getName(), fallbackPrefix, command);
        }

        @Override
        public void registerAll(String fallbackPrefix, List<Command> commands) {
            commands.forEach(c -> register(fallbackPrefix, c));
        }

        @Override
        public boolean dispatch(CommandSender sender, String cmdLine) {
            throw new UnsupportedOperationException("not needed in this test");
        }

        @Override
        public void clearCommands() {
            known.clear();
        }

        @Override
        public Command getCommand(String name) {
            return known.get(name);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String cmdLine) {
            return List.of();
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String cmdLine, Location location) {
            return List.of();
        }

        @Override
        public Map<String, Command> getKnownCommands() {
            return known;
        }
    }

    private static MenuSpec menu(String id, String openCommand) {
        return new MenuSpec(id, "Title " + id, 9, openCommand == null ? "" : openCommand,
                List.of(), List.of(), List.of());
    }

    private static MenuCommandBridge bridge() {
        // Minimal fixed catalog: the bridge only renders refusal notices with
        // it, and the registration logic under test never reaches messages.
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString("prefix: ''\nmenu:\n  player-only: x\n  unavailable: x\ncommon:\n  no-permission: x\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        MessageCatalog catalog = MessageCatalog.parse(yaml).catalog();
        return new MenuCommandBridge(Logger.getLogger("test"),
                (player, menuId) -> true, () -> catalog);
    }

    @Test
    void registersPerMenuCommandsWithPerMenuPermissions() {
        FakeCommandMap map = new FakeCommandMap();
        MenuCommandBridge bridge = bridge();
        bridge.sync(map, Map.of(
                "quests", menu("quests", "questsmenu"),
                "codex", menu("codex", "")));
        assertTrue(map.known.containsKey("questsmenu"));
        assertTrue(map.known.containsKey("rebellion:questsmenu"));
        assertEquals(List.of("questsmenu"), bridge.registeredLabels());
        Command cmd = map.known.get("questsmenu");
        assertEquals("rebellion.items.menu.quests", cmd.getPermission());
        assertEquals("quests", ((MenuCommandBridge.DynamicMenuCommand) cmd).menuId());
        assertFalse(map.known.containsKey("codex"), "menus without open_command stay unregistered");
    }

    @Test
    void refusesLabelsAlreadyTakenByOtherCommands() {
        FakeCommandMap map = new FakeCommandMap();
        // Some other plugin already owns /spawn.
        map.known.put("spawn", new StubCommand("spawn"));
        MenuCommandBridge bridge = bridge();
        bridge.sync(map, Map.of("home", menu("home", "spawn")));
        assertEquals(List.of(), bridge.registeredLabels());
        // Our stub still owns the label: no hijack happened.
        assertTrue(map.known.get("spawn") instanceof StubCommand,
                "dynamic menu command must not replace an existing command");
    }

    @Test
    void refusesDuplicatesAcrossMenusForEveryClaimant() {
        FakeCommandMap map = new FakeCommandMap();
        MenuCommandBridge bridge = bridge();
        bridge.sync(map, Map.of(
                "alpha", menu("alpha", "dupe"),
                "beta", menu("beta", "dupe")));
        assertEquals(List.of(), bridge.registeredLabels());
        assertFalse(map.known.containsKey("dupe"));
        assertFalse(map.known.containsKey("rebellion:dupe"));
    }

    @Test
    void resyncOnReloadSwapsRegisteredSets() {
        FakeCommandMap map = new FakeCommandMap();
        MenuCommandBridge bridge = bridge();
        bridge.sync(map, Map.of("old", menu("old", "oldmenu")));
        assertTrue(map.known.containsKey("oldmenu"));

        bridge.sync(map, Map.of("new", menu("new", "newmenu")));
        assertNull(map.known.get("oldmenu"), "stale command must not survive a resync");
        assertNull(map.known.get("rebellion:oldmenu"));
        assertTrue(map.known.containsKey("newmenu"));
        assertEquals(List.of("newmenu"), bridge.registeredLabels());
    }

    @Test
    void unregisterAllCleansEveryTrace() {
        FakeCommandMap map = new FakeCommandMap();
        MenuCommandBridge bridge = bridge();
        bridge.sync(map, orderedMenus(Map.of(
                "a", menu("a", "menua"),
                "b", menu("b", "menub"))));
        assertEquals(List.of("menua", "menub"), bridge.registeredLabels());
        bridge.unregisterAll(map);
        assertEquals(List.of(), bridge.registeredLabels());
        assertNull(map.known.get("menua"));
        assertNull(map.known.get("rebellion:menua"));
        assertNull(map.known.get("menub"));
        assertNull(map.known.get("rebellion:menub"));
    }

    private static Map<String, MenuSpec> orderedMenus(Map<String, MenuSpec> src) {
        Map<String, MenuSpec> out = new LinkedHashMap<>();
        // Deterministic order by menu id keeps registered-label assertions stable.
        src.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** Smallest possible Command subclass for occupying a label. */
    private static final class StubCommand extends Command {
        StubCommand(String name) {
            super(name);
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            return true;
        }
    }
}
