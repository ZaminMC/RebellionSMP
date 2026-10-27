package net.zaminmc.rebellion.core.db;

import java.util.List;
import net.zaminmc.rebellion.api.result.FailureCode;
import net.zaminmc.rebellion.api.result.RebellionException;

/**
 * Migration failure with a precise, actionable message. Carries the list of
 * issues so startup can print all of them. The caller must treat this as
 * fail-closed: dependent modules must not enable.
 */
public final class MigrationException extends RebellionException {

    private final List<String> issues;

    public MigrationException(List<String> issues) {
        super(FailureCode.PERSISTENCE_FAILURE, "Schema migration failed: " + String.join("; ", issues));
        this.issues = List.copyOf(issues);
    }

    public List<String> issues() {
        return issues;
    }
}
