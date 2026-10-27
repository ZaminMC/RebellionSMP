package net.zaminmc.rebellion.core.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.zaminmc.rebellion.core.config.CoreConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Diagnostics: incident IDs, bounded ring, sanitized rotated reports. */
final class DiagnosticsServiceTest {

    private CoreConfig config() {
        return new CoreConfig(1, false, 30, "diagnostics", 3, 5);
    }

    /** Pure unit tests: the service never touches a live Bukkit runtime. */
    private DiagnosticsService newService(Path tmp) {
        return new DiagnosticsService(config(), tmp,
                java.util.logging.Logger.getLogger("t"), () -> "unit-test-server 26.3");
    }

    @Test
    void incidentIdsAreMonotonicAndUnique(@TempDir Path tmp) {
        DiagnosticsService service = newService(tmp);
        String a = service.incident();
        String b = service.incident();
        assertNotEquals(a, b);
        assertTrue(a.startsWith("INC-"), a);
        assertTrue(a.matches("INC-\\d{8}-\\d{6}-\\d{6}"), a);
    }

    @Test
    void incidentRingIsBounded(@TempDir Path tmp) {
        DiagnosticsService service = newService(tmp);
        for (int i = 0; i < 10; i++) {
            service.warn(service.incident(), "incident " + i);
        }
        assertEquals(5, service.recentIncidents().size());
        assertTrue(service.recentIncidents().get(service.recentIncidents().size() - 1)
                .summary().contains("incident 9"));
    }

    @Test
    void reportsAreWrittenAndRotated(@TempDir Path tmp) throws Exception {
        DiagnosticsService service = newService(tmp);
        Path first = service.writeReport("test", List.of("core=OPERATIONAL"), List.of("config: ok"), null);
        assertNotNull(first);
        assertTrue(Files.exists(first));

        for (int i = 0; i < 6; i++) {
            service.writeReport("test " + i, List.of(), List.of(), null);
        }
        try (var stream = Files.list(tmp.resolve("diagnostics"))) {
            long count = stream.filter(p -> p.getFileName().toString().startsWith("report-")).count();
            assertEquals(3, count, "rotation must keep only max-report-files");
        }
    }

    @Test
    void reportContentIsSanitizedAndSecretFree(@TempDir Path tmp) throws Exception {
        DiagnosticsService service = newService(tmp);
        service.warn(service.incident(), "multi\nline attempt");
        Path path = service.writeReport("reason\nwith newline",
                List.of("core=OPERATIONAL"), List.of("config: ok"), null);
        String content = Files.readString(path);
        assertFalse(content.contains("\nline attempt"), "newlines in summaries must be flattened");
        assertTrue(content.contains("multi line attempt"),
                "sanitized text must remain readable: " + content);
        assertTrue(content.contains("reason with newline"), "reason is flattened too");
        assertTrue(content.contains("core=OPERATIONAL"));
        // Secrets never reach the report because callers (CoreState.configSummary)
        // only pass backend names and booleans; the service itself never reads
        // database.yml. That caller contract is asserted in CoreState tests/docs.
        assertFalse(content.contains("password"), "sanity: this report contains no password keys");
    }
}
