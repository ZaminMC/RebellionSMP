package net.zaminmc.rebellion.core.config;

/**
 * One configuration validation problem: the YAML path and a precise, actionable
 * explanation. Validation collects every issue before failing so admins fix all
 * errors in one pass.
 */
public record ValidationIssue(String path, String message) {

    @Override
    public String toString() {
        return path + ": " + message;
    }
}
