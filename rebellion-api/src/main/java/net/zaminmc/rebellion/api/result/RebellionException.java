package net.zaminmc.rebellion.api.result;

/**
 * Runtime exception carrying a stable {@link FailureCode} and an optional
 * diagnostics incident ID. Use for exceptional control flow across module
 * boundaries; prefer result values inside a module.
 */
public class RebellionException extends RuntimeException {

    private final FailureCode code;
    private final String incidentId; // may be null

    public RebellionException(FailureCode code, String message) {
        this(code, message, null, null);
    }

    public RebellionException(FailureCode code, String message, String incidentId) {
        this(code, message, incidentId, null);
    }

    public RebellionException(FailureCode code, String message, String incidentId, Throwable cause) {
        super(message, cause);
        this.code = java.util.Objects.requireNonNull(code, "code");
        this.incidentId = incidentId;
    }

    public FailureCode code() {
        return code;
    }

    /** @return diagnostics incident id to quote in user-facing errors, or null. */
    public String incidentId() {
        return incidentId;
    }
}
