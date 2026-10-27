package net.zaminmc.rebellion.api.database;

import java.util.List;

/**
 * Module-owned schema migration description (ADR-0005). Modules pass these to
 * {@link RebellionDatabase#applyModuleMigrations(String, List)}; Core converts
 * them to internal migrations, computes checksums and applies them with the
 * same guarantees as boot-time migrations (gap-free versions, checksum
 * verification, downgrade refusal, atomic bookkeeping).
 *
 * <p>Statements must be backend-portable DDL/DML constants — no runtime
 * interpolation. If a backend needs different syntax (e.g. auto-increment),
 * the module selects statements based on {@link RebellionDatabase#backend()}.</p>
 *
 * @param version     monotonically increasing, gap-free within the module set
 * @param description shown in rb_schema_migrations and diagnostics
 * @param statements  executed in order; each must be non-blank
 */
public record MigrationSpec(int version, String description, List<String> statements) {

    public MigrationSpec {
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
}
