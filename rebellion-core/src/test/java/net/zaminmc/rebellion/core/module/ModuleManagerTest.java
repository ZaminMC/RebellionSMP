package net.zaminmc.rebellion.core.module;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import net.zaminmc.rebellion.api.module.ModuleDescriptor;
import net.zaminmc.rebellion.api.module.ModuleState;
import org.junit.jupiter.api.Test;

/** Module registry semantics: registration, atomic updates, snapshots. */
final class ModuleManagerTest {

    @Test
    void registerAndUpdateLifecycle() {
        ModuleManager manager = new ModuleManager();
        ModuleDescriptor desc = new ModuleDescriptor("teams", "RebellionTeams", "0.1.0");
        manager.register(desc);
        assertTrue(manager.health("teams").isPresent());
        assertEquals(ModuleState.PENDING, manager.health("teams").get().state());

        manager.update("teams", ModuleState.OPERATIONAL, "");
        assertEquals(ModuleState.OPERATIONAL, manager.health("teams").get().state());

        // Updating an unregistered module is ignored, never creates ghost entries.
        manager.update("ghost", ModuleState.OPERATIONAL, "");
        assertTrue(manager.health("ghost").isEmpty());
        assertEquals(1, manager.snapshot().size());

        manager.unregister("teams");
        assertTrue(manager.health("teams").isEmpty());
    }

    @Test
    void doubleRegistrationKeepsFirstDescriptor() {
        ModuleManager manager = new ModuleManager();
        manager.register(new ModuleDescriptor("core", "RebellionCore", "1.0"));
        manager.register(new ModuleDescriptor("core", "Duplicate", "2.0"));
        assertEquals("RebellionCore", manager.health("core").get().descriptor().displayName());
    }

    @Test
    void unhealthyAndRatioReportCorrectly() {
        ModuleManager manager = new ModuleManager();
        manager.register(new ModuleDescriptor("core", "Core", "1"));
        manager.register(new ModuleDescriptor("teams", "Teams", "1"));
        manager.register(new ModuleDescriptor("items", "Items", "1"));
        manager.update("core", ModuleState.OPERATIONAL, "");
        manager.update("teams", ModuleState.DEGRADED, "waiting on core persistence");
        manager.update("items", ModuleState.OPERATIONAL, "");

        assertEquals("2/3", manager.healthyRatio());
        assertEquals(1, manager.unhealthy().size());
        assertEquals("teams", manager.unhealthy().get(0).descriptor().moduleKey());
        assertEquals("waiting on core persistence", manager.unhealthy().get(0).detail());
    }

    @Test
    void snapshotIsACopy() {
        ModuleManager manager = new ModuleManager();
        manager.register(new ModuleDescriptor("core", "Core", "1"));
        // The returned snapshot is immutable — callers cannot corrupt the registry.
        assertThrows(UnsupportedOperationException.class, () -> manager.snapshot().clear());
        // A caller-made mutable copy is fully detached from the registry.
        List<net.zaminmc.rebellion.api.module.ModuleHealth> copy =
                new ArrayList<>(manager.snapshot());
        copy.clear();
        assertEquals(1, manager.snapshot().size());
    }

    @Test
    void descriptorValidation() {
        assertThrows(IllegalArgumentException.class, () -> new ModuleDescriptor("CORE", "x", "1"));
        assertThrows(IllegalArgumentException.class, () -> new ModuleDescriptor("", "x", "1"));
        assertThrows(IllegalArgumentException.class, () -> new ModuleDescriptor("bad key", "x", "1"));
        assertThrows(IllegalArgumentException.class, () -> new ModuleDescriptor("1abc", "x", "1"));
        assertEquals("dragons", new ModuleDescriptor("dragons", "Dragons", "1").moduleKey());
    }
}
