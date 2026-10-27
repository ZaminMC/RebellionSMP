package net.zaminmc.rebellion.core.config;

import java.util.Map;

/**
 * Validated database configuration (from {@code database.yml}).
 *
 * <p>SQLite uses exactly one pooled connection (SQLite permits one writer and
 * serializing access eliminates lock errors); pool sizing therefore only applies
 * to MySQL.</p>
 *
 * @param backend             SQLITE or MYSQL
 * @param sqliteFile          file name under the Core data directory
 * @param mysqlHost           host or empty for unix socket via URL override
 * @param mysqlPort           1..65535
 * @param mysqlDatabase       schema name
 * @param mysqlUsername       user name
 * @param mysqlPassword       already environment-expanded; may be empty
 * @param mysqlProperties     extra URL properties, passed verbatim
 * @param maxPoolSize         MySQL pool size (forced to 1 for SQLite)
 * @param minIdle             MySQL min idle
 * @param connectionTimeoutMs Hikari connection acquisition timeout
 * @param maxLifetimeMs       Hikari max connection lifetime
 * @param executorPoolSize    persistence executor threads
 * @param executorQueueCapacity hard backpressure bound; rejected work has no side effects
 * @param strictChecksums     fail when an applied migration's checksum changed
 */
public record DatabaseConfig(
        Backend backend,
        String sqliteFile,
        String mysqlHost,
        int mysqlPort,
        String mysqlDatabase,
        String mysqlUsername,
        String mysqlPassword,
        Map<String, String> mysqlProperties,
        int maxPoolSize,
        int minIdle,
        int connectionTimeoutMs,
        int maxLifetimeMs,
        int executorPoolSize,
        int executorQueueCapacity,
        boolean strictChecksums) {

    public enum Backend { SQLITE, MYSQL }

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** SQLite always runs with a single connection; see class javadoc. */
    public int effectiveMaxPoolSize() {
        return backend == Backend.SQLITE ? 1 : maxPoolSize;
    }

    public int effectiveMinIdle() {
        return backend == Backend.SQLITE ? 1 : Math.min(minIdle, maxPoolSize);
    }

    /** @return JDBC URL for the configured backend. */
    public String jdbcUrl() {
        if (backend == Backend.SQLITE) {
            return "jdbc:sqlite:" + sqliteFile;
        }
        StringBuilder url = new StringBuilder("jdbc:mysql://")
                .append(mysqlHost).append(':').append(mysqlPort).append('/').append(mysqlDatabase);
        if (!mysqlProperties.isEmpty()) {
            String params = mysqlProperties.entrySet().stream()
                    .map(e -> e.getKey() + "=" + urlEncode(e.getValue()))
                    .reduce((a, b) -> a + "&" + b)
                    .orElse("");
            url.append('?').append(params);
        }
        return url.toString();
    }

    private static String urlEncode(String v) {
        return java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8);
    }
}
