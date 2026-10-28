package net.zaminmc.rebellion.items.menu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Menu loading, validation and evaluation. The material validator and the
 * permission checker are injected, so the whole engine is testable without a
 * live server; the Bukkit host only forwards inventory events.
 */
final class MenuEngineTest {

    private static final Set<String> KNOWN = Set.of(
            "DIRT", "BARRIER", "DIAMOND", "STONE", "IRON_INGOT", "HEART_OF_THE_SEA",
            "DIAMOND_SWORD", "ELYTRA", "DRAGON_EGG", "NETHERITE_INGOT", "NETHER_STAR",
            "ENCHANTED_GOLDEN_APPLE", "COPPER_INGOT", "AMETHYST_SHARD", "ECHO_SHARD",
            "WRITABLE_BOOK", "ENDER_EYE", "ENCHANTED_BOOK", "SOUL_LANTERN");

    /** Records everything a MenuSubject invocation did. */
    private static final class RecordingSubject implements MenuSubject {
        final Set<String> permissions = new java.util.HashSet<>();
        final List<String> events = new ArrayList<>();
        boolean allowConsole = true;

        @Override
        public boolean hasPermission(String permission) {
            return permissions.contains(permission);
        }

        @Override
        public void sendMessage(String miniMessage) {
            events.add("message:" + miniMessage);
        }

        @Override
        public void playSound(String soundKey) {
            events.add("sound:" + soundKey);
        }

        @Override
        public void runPlayerCommand(String command) {
            events.add("player:" + command);
        }

        @Override
        public void runConsoleCommand(String command) {
            if (!allowConsole) {
                events.add("console-refused:" + command);
                return;
            }
            events.add("console:" + command);
        }

        @Override
        public void closeMenu() {
            events.add("close");
        }
    }

    private MenuLoader loader() {
        return new MenuLoader(name -> KNOWN.contains(name));
    }

    // ------------------------------------------------------------------
    // Loading + validation
    // ------------------------------------------------------------------

    private YamlConfiguration validMenu() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("menu_title", "<gold>Test</gold>");
        yaml.set("size", 9);
        yaml.set("items.a.material", "DIRT");
        yaml.set("items.a.slot", 0);
        yaml.set("items.a.display_name", "<gray>Test</gray>");
        yaml.set("items.a.lore", List.of("<gray>line</gray>"));
        yaml.set("items.a.left_click_commands", List.of("[message] hi", "[close]"));
        return yaml;
    }

    @Test
    void validMenuParses() {
        var loaded = loader().load("test", validMenu());
        assertTrue(loaded.valid(), () -> loaded.issues().toString());
        MenuSpec spec = loaded.spec();
        assertEquals("test", spec.id());
        assertEquals(9, spec.size());
        assertEquals(1, spec.items().size());
        var item = spec.items().get(0);
        assertEquals("DIRT", item.material());
        assertEquals(1, item.amount());
        assertEquals(2, item.leftClickActions().size());
        assertEquals(MenuSpec.Action.Type.MESSAGE, item.leftClickActions().get(0).type());
        assertEquals("hi", item.leftClickActions().get(0).value());
        assertEquals(MenuSpec.Action.Type.CLOSE, item.leftClickActions().get(1).type());
    }

    @Test
    void sizeMustBeNineToFiftyFour() {
        for (int bad : new int[] {0, 3, 10, 55, -9, 63}) {
            YamlConfiguration yaml = validMenu();
            yaml.set("size", bad);
            assertFalse(loader().load("test", yaml).valid(), "size " + bad + " must fail");
        }
        for (int good : new int[] {9, 18, 27, 36, 45, 54}) {
            YamlConfiguration yaml = validMenu();
            yaml.set("size", good);
            assertTrue(loader().load("test", yaml).valid(),
                    () -> loader().load("test", yaml).issues().toString());
        }
    }

    @Test
    void unknownMaterialIsPreciseIssue() {
        YamlConfiguration yaml = validMenu();
        yaml.set("items.a.material", "UNOBTAINIUM");
        var loaded = loader().load("test", yaml);
        assertFalse(loaded.valid());
        assertTrue(loaded.issues().stream().anyMatch(i -> i.contains("items.a.material")
                && i.contains("UNOBTAINIUM")));
    }

    @Test
    void duplicateSlotsAreRejected() {
        YamlConfiguration yaml = validMenu();
        yaml.set("items.b.material", "STONE");
        yaml.set("items.b.slot", 0);
        var loaded = loader().load("test", yaml);
        assertFalse(loaded.valid());
        assertTrue(loaded.issues().stream().anyMatch(i -> i.contains("already used")));
    }

    @Test
    void slotOutOfMenuBoundsIsRejected() {
        YamlConfiguration yaml = validMenu();
        yaml.set("items.a.slot", 9); // size 9 → 0..8
        var loaded = loader().load("test", yaml);
        assertFalse(loaded.valid());
        assertTrue(loaded.issues().stream().anyMatch(i -> i.contains("out of menu bounds")));
    }

    @Test
    void slotsListIsSupported() {
        YamlConfiguration yaml = validMenu();
        yaml.set("size", 27);
        yaml.set("items.b.material", "STONE");
        yaml.set("items.b.slots", List.of(10, 12, 14));
        var loaded = loader().load("test", yaml);
        assertTrue(loaded.valid(), () -> loaded.issues().toString());
        assertEquals(4, loaded.spec().bySlot().size());
    }

    @Test
    void missingSlotIsIssue() {
        YamlConfiguration yaml = validMenu();
        yaml.set("items.a.slot", null);
        var loaded = loader().load("test", yaml);
        assertFalse(loaded.valid());
        assertTrue(loaded.issues().stream().anyMatch(i -> i.contains("needs 'slot'")));
    }

    @Test
    void amountBoundsAreValidated() {
        YamlConfiguration yaml = validMenu();
        yaml.set("items.a.amount", 65);
        assertTrue(loader().load("test", yaml).issues().stream()
                .anyMatch(i -> i.contains("amount")));
        YamlConfiguration yaml2 = validMenu();
        yaml2.set("items.a.amount", 0);
        assertTrue(loader().load("test", yaml2).issues().stream()
                .anyMatch(i -> i.contains("amount")));
    }

    @Test
    void openCommandMustBePlainIdentifier() {
        YamlConfiguration bad = validMenu();
        bad.set("open_command", "bad command with spaces!");
        assertFalse(loader().load("test", bad).valid());
        YamlConfiguration good = validMenu();
        good.set("open_command", "item_codex");
        var loaded = loader().load("test", good);
        assertTrue(loaded.valid());
        assertEquals("item_codex", loaded.spec().openCommand());
    }

    @Test
    void unknownRequirementTypeIsPreciseDiagnostic() {
        YamlConfiguration yaml = validMenu();
        yaml.set("items.a.view_requirement.requirements.has_money.type", "has money");
        yaml.set("items.a.view_requirement.requirements.has_money.value", "100");
        var loaded = loader().load("test", yaml);
        assertFalse(loaded.valid());
        assertTrue(loaded.issues().stream().anyMatch(i -> i.contains("unsupported requirement type")
                && i.contains("has money")));
    }

    @Test
    void permissionRequirementParses() {
        YamlConfiguration yaml = validMenu();
        yaml.set("items.a.left_click_requirement.requirements.staff.type", "has permission");
        yaml.set("items.a.left_click_requirement.requirements.staff.value", "rebellion.test.staff");
        var loaded = loader().load("test", yaml);
        assertTrue(loaded.valid(), () -> loaded.issues().toString());
        var req = loaded.spec().items().get(0).leftClickRequirements().get(0);
        assertEquals(MenuSpec.Requirement.Type.HAS_PERMISSION, req.type());
        assertEquals("rebellion.test.staff", req.value());
    }

    @Test
    void requirementWithoutValueIsIssue() {
        YamlConfiguration yaml = validMenu();
        yaml.set("items.a.left_click_requirement.requirements.x.type", "has permission");
        var loaded = loader().load("test", yaml);
        assertFalse(loaded.valid());
        assertTrue(loaded.issues().stream().anyMatch(i -> i.contains("missing permission node")));
    }

    @Test
    void unsupportedActionTypeIsPreciseDiagnostic() {
        YamlConfiguration yaml = validMenu();
        yaml.set("items.a.left_click_commands", List.of("[connect] survival"));
        var loaded = loader().load("test", yaml);
        assertFalse(loaded.valid());
        assertTrue(loaded.issues().stream().anyMatch(i -> i.contains("unsupported action type")
                && i.contains("connect")));
    }

    @Test
    void actionSyntaxErrorsAreReported() {
        YamlConfiguration yaml = validMenu();
        yaml.set("items.a.left_click_commands", List.of("message without brackets"));
        var loaded = loader().load("test", yaml);
        assertFalse(loaded.valid());
        assertTrue(loaded.issues().stream().anyMatch(i -> i.contains("must start with [type]")));

        YamlConfiguration yaml2 = validMenu();
        yaml2.set("items.a.left_click_commands", List.of("[unclosed"));
        assertFalse(loader().load("test", yaml2).valid());

        YamlConfiguration yaml3 = validMenu();
        yaml3.set("items.a.left_click_commands", List.of("[message]"));
        assertTrue(loader().load("test", yaml3).issues().stream()
                .anyMatch(i -> i.contains("needs a payload")));
    }

    @Test
    void commandActionsNeedLeadingSlash() {
        YamlConfiguration yaml = validMenu();
        yaml.set("items.a.left_click_commands", List.of("[player] rebellionitems status"));
        var loaded = loader().load("test", yaml);
        assertFalse(loaded.valid());
        assertTrue(loaded.issues().stream()
                .anyMatch(i -> i.contains("[player] action payload must start with '/'")));
    }

    @Test
    void consoleActionParsesButIsGatedAtExecution() {
        YamlConfiguration yaml = validMenu();
        yaml.set("items.a.left_click_commands", List.of("[console] /say hello"));
        var loaded = loader().load("test", yaml);
        assertTrue(loaded.valid(), () -> loaded.issues().toString());
        assertEquals(MenuSpec.Action.Type.CONSOLE_COMMAND,
                loaded.spec().items().get(0).leftClickActions().get(0).type());
    }

    // ------------------------------------------------------------------
    // Requirement + action evaluation
    // ------------------------------------------------------------------

    @Test
    void requirementFailureBlocksAllActions() {
        MenuSpec.Requirement req = new MenuSpec.Requirement(
                MenuSpec.Requirement.Type.HAS_PERMISSION, "rebellion.vip");
        List<MenuSpec.Action> actions = List.of(
                new MenuSpec.Action(MenuSpec.Action.Type.MESSAGE, "secret"));
        RecordingSubject subject = new RecordingSubject();
        assertFalse(MenuEvaluator.handleClick(List.of(req), actions, subject));
        assertTrue(subject.events.isEmpty(), "no action side effects on denied click");
    }

    @Test
    void requirementPassExecutesInOrder() {
        List<MenuSpec.Action> actions = List.of(
                new MenuSpec.Action(MenuSpec.Action.Type.SOUND, "UI_BUTTON_CLICK"),
                new MenuSpec.Action(MenuSpec.Action.Type.MESSAGE, "hi"),
                new MenuSpec.Action(MenuSpec.Action.Type.CLOSE, ""));
        RecordingSubject subject = new RecordingSubject();
        assertTrue(MenuEvaluator.handleClick(List.of(), actions, subject));
        assertEquals(List.of("sound:UI_BUTTON_CLICK", "message:hi", "close"), subject.events);
    }

    @Test
    void consoleActionsRespectHostGate() {
        MenuSpec.Action console = new MenuSpec.Action(
                MenuSpec.Action.Type.CONSOLE_COMMAND, "/say hello");
        RecordingSubject allowed = new RecordingSubject();
        allowed.allowConsole = true;
        MenuEvaluator.execute(List.of(console), allowed);
        assertEquals(List.of("console:/say hello"), allowed.events);

        RecordingSubject denied = new RecordingSubject();
        denied.allowConsole = false;
        MenuEvaluator.execute(List.of(console), denied);
        assertEquals(List.of("console-refused:/say hello"), denied.events);
    }

    @Test
    void emptyRequirementListAlwaysPasses() {
        assertTrue(MenuEvaluator.requirementsMet(List.of(), new RecordingSubject()));
    }

    // ------------------------------------------------------------------
    // Registry (gui_menus pattern)
    // ------------------------------------------------------------------

    @Test
    void registryLoadsBundledMenusFromDisk(@TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path menus = tmp.resolve("menus");
        java.nio.file.Files.createDirectories(menus);
        try (var in = MenuEngineTest.class.getResourceAsStream("/menus/item_codex.yml")) {
            java.nio.file.Files.copy(in, menus.resolve("item_codex.yml"));
        }
        try (var in = MenuEngineTest.class.getResourceAsStream("/menus/talisman_guide.yml")) {
            java.nio.file.Files.copy(in, menus.resolve("talisman_guide.yml"));
        }
        try (var in = MenuEngineTest.class.getResourceAsStream("/menus/ritual_guide.yml")) {
            java.nio.file.Files.copy(in, menus.resolve("ritual_guide.yml"));
        }
        MenuRegistry registry = new MenuRegistry(menus, loader(),
                java.util.logging.Logger.getLogger("test"));
        Map<String, String> registered = new HashMap<>();
        registered.put("item_codex", "item_codex.yml");
        registered.put("talisman_guide", "talisman_guide.yml");
        registered.put("ritual_guide", "ritual_guide.yml");
        var result = registry.loadCandidate(registered);
        assertTrue(result.issues().isEmpty(), () -> result.issues().toString());
        assertTrue(registry.activate(result));
        assertEquals(Set.of("item_codex", "talisman_guide", "ritual_guide"),
                registry.active().keySet());
        assertEquals(27, registry.active().get("item_codex").size());
        assertEquals(18, registry.active().get("talisman_guide").size());
        assertEquals(27, registry.active().get("ritual_guide").size());
        // The bundled close button uses the [close] action.
        assertTrue(registry.active().get("talisman_guide").items().stream()
                .flatMap(item -> item.leftClickActions().stream())
                .anyMatch(action -> action.type() == MenuSpec.Action.Type.CLOSE));
        // The ritual guide's discovery state rides the shared placeholder
        // contract - a rename of the shipped knowledge key must fail loudly.
        assertTrue(registry.active().get("ritual_guide").items().stream()
                .flatMap(item -> item.lore().stream())
                .anyMatch(line -> line.contains(
                        "%rebellion_ritual_ritual_soul_communion_discovered%")),
                "ritual_guide must carry the communion discovered-state token");
    }

    @Test
    void registryRejectsTraversalAndBadIds(@TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path menus = tmp.resolve("menus");
        java.nio.file.Files.createDirectories(menus);
        java.nio.file.Files.writeString(menus.resolve("evil.yml"), "menu_title: x\nsize: 9\nitems: {}");
        MenuRegistry registry = new MenuRegistry(menus, loader(),
                java.util.logging.Logger.getLogger("test"));

        Map<String, String> bad = new HashMap<>();
        bad.put("UPPER_CASE_ID", "evil.yml");
        bad.put("trav", "../evil.yml");
        bad.put("absolute", "/etc/passwd");
        bad.put("missing", "nope.yml");
        var result = registry.loadCandidate(bad);
        assertFalse(result.issues().isEmpty());
        assertTrue(result.menus().isEmpty());
        assertEquals(4, result.issues().size());
    }

    @Test
    void registryKeepsPreviousMenusWhenCandidateIsInvalid(@TempDir java.nio.file.Path tmp)
            throws Exception {
        java.nio.file.Path menus = tmp.resolve("menus");
        java.nio.file.Files.createDirectories(menus);
        try (var in = MenuEngineTest.class.getResourceAsStream("/menus/item_codex.yml")) {
            java.nio.file.Files.copy(in, menus.resolve("item_codex.yml"));
        }
        java.nio.file.Files.writeString(menus.resolve("broken.yml"),
                "menu_title: x\nsize: 12\nitems: {}");
        MenuRegistry registry = new MenuRegistry(menus, loader(),
                java.util.logging.Logger.getLogger("test"));

        var first = registry.loadCandidate(Map.of("item_codex", "item_codex.yml"));
        assertTrue(registry.activate(first));
        assertEquals(1, registry.active().size());

        var second = registry.loadCandidate(Map.of("item_codex", "broken.yml"));
        assertFalse(registry.activate(second));
        // Previous valid menu stays active — atomic reload contract.
        assertEquals(1, registry.active().size());
        assertEquals(27, registry.active().get("item_codex").size());
    }

    @Test
    void missingItemsSectionIsIssue() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("menu_title", "x");
        yaml.set("size", 9);
        var loaded = loader().load("test", yaml);
        assertFalse(loaded.valid());
        assertTrue(loaded.issues().stream().anyMatch(i -> i.contains("items: missing section")));
    }
}
