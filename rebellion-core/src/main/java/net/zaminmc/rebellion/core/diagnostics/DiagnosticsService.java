package net.zaminmc.rebellion.core.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.zaminmc.rebellion.core.config.CoreConfig;

/**
 * Structured diagnostics: monotonic incident IDs, a bounded in-memory incident
 * ring, and rotated report files with sanitized content.
 *
 * <p>Reports never contain credentials, JDBC URLs with passwords or raw player
 * data. Secret-bearing configuration is represented by backend name only.
 * Incident summaries are kept short; stack traces go to the server log and are
 * referenced, not duplicated, unless the report is written for an exception.</p>
 */
public final class DiagnosticsService {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final Logger logger;
    private final Path reportDirectory;
    private final int maxReportFiles;
    private final int recentCapacity;
    private final ArrayDeque<Incident> recent;
    private final AtomicLong sequence = new AtomicLong();
    private final Object writeLock = new Object();
    private final java.util.function.Supplier<String> serverVersion;

    /**
     * Production constructor. The server version is read lazily through a supplier
     * so unit tests can construct this service without a live Bukkit runtime; the
     * supplier itself must never leak credentials.
     *
     * @param config validated core configuration
     */
    public DiagnosticsService(CoreConfig config, Path dataDirectory, Logger logger) {
        this(config, dataDirectory, logger, () -> {
            try {
                return org.bukkit.Bukkit.getVersion();
            } catch (RuntimeException noServer) {
                // Bukkit.getServer() throws IllegalStateException before the server
                // is fully constructed (e.g. very early startup diagnostics).
                return "server not initialized";
            }
        });
    }

    /** Test/Embedder constructor with an explicit version provider. */
    public DiagnosticsService(CoreConfig config, Path dataDirectory, Logger logger,
            java.util.function.Supplier<String> serverVersion) {
        this.logger = logger;
        this.reportDirectory = dataDirectory.resolve(config.diagnosticsDirectory());
        this.maxReportFiles = config.maxReportFiles();
        this.recentCapacity = config.recentIncidentCapacity();
        this.recent = new ArrayDeque<>(config.recentIncidentCapacity());
        this.serverVersion = serverVersion;
    }

    /** Creates a new incident ID like {@code INC-20261028-000042}. */
    public String incident() {
        long seq = sequence.incrementAndGet();
        return "INC-" + TS.format(Instant.now()) + "-" + String.format("%06d", seq % 1_000_000);
    }

    /** Records a warning-level incident without an exception. */
    public void warn(String incidentId, String summary) {
        record(incidentId, "WARN", summary, null);
        logger.warning("[" + incidentId + "] " + sanitize(summary));
    }

    /** Records an error-level incident with its exception (logged, not stored raw). */
    public void error(String incidentId, String summary, Throwable throwable) {
        record(incidentId, "ERROR", summary, throwable);
        String flat = sanitize(summary);
        logger.severe("[" + incidentId + "] " + flat);
        if (throwable != null) {
            logger.severe("  cause: " + throwable.getClass().getSimpleName()
                    + ": " + throwable.getMessage());
        }
    }

    private void record(String incidentId, String level, String summary, Throwable t) {
        synchronized (recent) {
            if (recent.size() >= recentCapacity) {
                recent.pollFirst();
            }
            // Summaries are flattened at record time: incident lines must stay
            // single-line no matter which render path (log, report, API) reads them.
            recent.addLast(new Incident(incidentId, level, sanitize(summary),
                    t == null ? "" : t.getClass().getName(), Instant.now()));
        }
    }

    /** @return snapshot of recent incidents, oldest first. */
    public List<Incident> recentIncidents() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    /**
     * Writes a sanitized diagnostic report and returns its path.
     *
     * @param reason        short trigger description, e.g. "admin request" or "module failure"
     * @param moduleStates  formatted module health lines supplied by the caller
     * @param configSummary formatted config validation summary (must be pre-sanitized)
     */
    public Path writeReport(String reason, List<String> moduleStates, List<String> configSummary,
            Throwable cause) {
        synchronized (writeLock) {
            String incidentId = incident();
            Path file;
            try {
                Files.createDirectories(reportDirectory);
                file = reportDirectory.resolve("report-" + TS.format(Instant.now()) + "-" + incidentId + ".txt");
            } catch (IOException e) {
                logger.severe("Cannot create diagnostics directory " + reportDirectory + ": " + e.getMessage());
                return null;
            }

            StringBuilder sb = new StringBuilder(1024);
            sb.append("RebellionSMP diagnostic report\n");
            sb.append("incident:   ").append(incidentId).append('\n');
            sb.append("reason:     ").append(sanitize(reason)).append('\n');
            sb.append("written:    ").append(Instant.now()).append('\n');
            String version;
            try {
                version = serverVersion.get();
            } catch (RuntimeException e) {
                version = "unavailable: " + e.getClass().getSimpleName();
            }
            sb.append("server:     ").append(sanitize(version)).append('\n');
            sb.append("java:       ").append(System.getProperty("java.vm.name", "?"))
                    .append(' ').append(System.getProperty("java.version", "?")).append('\n');
            sb.append("\n== modules ==\n");
            moduleStates.forEach(line -> sb.append("  ").append(sanitize(line)).append('\n'));
            sb.append("\n== configuration ==\n");
            configSummary.forEach(line -> sb.append("  ").append(sanitize(line)).append('\n'));
            sb.append("\n== recent incidents ==\n");
            List<Incident> incidents = recentIncidents();
            if (incidents.isEmpty()) {
                sb.append("  (none)\n");
            } else {
                for (Incident i : incidents) {
                    sb.append("  ").append(i.toString()).append('\n');
                }
            }
            if (cause != null) {
                sb.append("\n== cause ==\n");
                sb.append("  ").append(cause.getClass().getName()).append(": ")
                        .append(sanitize(String.valueOf(cause.getMessage()))).append('\n');
            }

            try {
                Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                logger.severe("Cannot write diagnostic report " + file + ": " + e.getMessage());
                return null;
            }
            rotate();
            logger.info("Diagnostic report written: " + file.getFileName() + " (incident " + incidentId + ")");
            return file;
        }
    }

    private void rotate() {
        try {
            List<Path> reports = new ArrayList<>();
            try (var stream = Files.list(reportDirectory)) {
                stream.filter(p -> p.getFileName().toString().startsWith("report-"))
                        .forEach(reports::add);
            }
            if (reports.size() > maxReportFiles) {
                reports.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
                for (Path p : reports.subList(maxReportFiles, reports.size())) {
                    Files.deleteIfExists(p);
                }
            }
        } catch (IOException e) {
            logger.warning("Report rotation failed: " + e.getMessage());
        }
    }

    /** Removes newlines/control characters; incident summaries are single-line. */
    private static String sanitize(String line) {
        if (line == null) {
            return "";
        }
        return line.replaceAll("[\\r\\n\\t]", " ").replace((char) 0, ' ');
    }

    /** One recorded incident. */
    public record Incident(String id, String level, String summary, String exceptionType, Instant at) {
        @Override
        public String toString() {
            return at + " [" + level + "] " + id + " " + summary
                    + (exceptionType.isEmpty() ? "" : " (" + exceptionType + ")");
        }
    }
}
