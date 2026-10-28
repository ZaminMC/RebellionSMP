package net.zaminmc.rebellion.economy.db;

import java.util.List;
import net.zaminmc.rebellion.api.database.MigrationSpec;

/**
 * Economy schema, version 1 (ADR-0021). One namespaced set under module key
 * {@code economy}; portable between SQLite and MySQL (server-issued UUID
 * entry ids, no auto-increment, no backend-specific types).
 *
 * <p>Structural guarantees:</p>
 * <ul>
 *   <li>{@code rb_economy_accounts} — {@code PRIMARY KEY (account_uuid)}:
 *       one row per account; the balance is a {@code BIGINT} minor-unit
 *       count mutated only by guarded accumulates inside the mutation
 *       transactions (never negative, never above the configured maximum —
 *       both checked before the write).</li>
 *   <li>{@code rb_economy_ledger} — {@code PRIMARY KEY (entry_id)} with
 *       {@code UNIQUE (account_uuid, idempotency_key)}: the append-only
 *       audit history. Idempotency is structural — a replayed (account,
 *       key) pair loses the constraint and resolves to the typed no-op
 *       carrying the original entry. {@code balance_after} pins the exact
 *       post-mutation balance so any account row can be reconstructed and
 *       audited from the ledger alone.</li>
 * </ul>
 */
public final class EconomySchema {

    private EconomySchema() {
    }

    /** @return the v1 migration (identical for every backend). */
    public static MigrationSpec v1() {
        return new MigrationSpec(1,
                "economy v1: accounts + append-only idempotent ledger",
                List.of(
                        compact("""
                        CREATE TABLE rb_economy_accounts (
                          account_uuid CHAR(36) NOT NULL,
                          balance BIGINT NOT NULL,
                          updated_utc BIGINT NOT NULL,
                          PRIMARY KEY (account_uuid)
                        )
                        """),
                        compact("""
                        CREATE TABLE rb_economy_ledger (
                          entry_id CHAR(36) NOT NULL,
                          account_uuid CHAR(36) NOT NULL,
                          idempotency_key VARCHAR(96) NOT NULL,
                          reason VARCHAR(64) NOT NULL,
                          delta BIGINT NOT NULL,
                          balance_after BIGINT NOT NULL,
                          created_utc BIGINT NOT NULL,
                          PRIMARY KEY (entry_id),
                          CONSTRAINT rb_economy_ledger_key_unique
                            UNIQUE (account_uuid, idempotency_key)
                        )
                        """)));
    }

    /** @return the full registry, versions 1..N (monotonic, gap-free). */
    public static List<MigrationSpec> migrations() {
        return List.of(v1());
    }

    private static String compact(String sql) {
        return sql.replaceAll("\n\s*", " ").trim();
    }
}
