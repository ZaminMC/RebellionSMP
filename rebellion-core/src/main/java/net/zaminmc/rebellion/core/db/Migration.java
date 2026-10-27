package net.zaminmc.rebellion.core.db;

import java.util.List;

/**
 * One schema migration belonging to a module's namespaced set.
 *
 * @param version     monotonically increasing, gap-free per module (validated)
 * @param description shown in schema_migrations and diagnostics
 * @param statements  executed in order inside one transaction (see caveat for
 *                    MySQL DDL in docs/DATA_MODEL.md)
 */
public record Migration(int version, String description, List<String> statements) {

    public Migration {
        if (version < 1) {
            throw new IllegalArgumentException("migration version must be >= 1");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("migration description must not be blank");
        }
        statements = List.copyOf(statements);
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("migration " + version + " has no statements");
        }
        for (String s : statements) {
            if (s == null || s.isBlank()) {
                throw new IllegalArgumentException(
                        "migration " + version + " contains a blank statement");
            }
        }
    }

    /** SHA-256 over the statements, used to detect accidental edits. */
    public String checksum() {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            for (String s : statements) {
                md.update(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
