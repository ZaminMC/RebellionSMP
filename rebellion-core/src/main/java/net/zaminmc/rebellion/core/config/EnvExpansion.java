package net.zaminmc.rebellion.core.config;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands {@code ${ENV_NAME}} and {@code ${ENV_NAME:fallback}} references inside
 * configuration values so credentials never need to be written to disk.
 *
 * <p>Unknown variables without fallback produce a validation error rather than an
 * empty string, because an empty password caused by a typo'd variable name must
 * be reported, not silently used.</p>
 */
public final class EnvExpansion {

    private static final Pattern REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}");

    private EnvExpansion() {
    }

    /**
     * @param raw     value possibly containing references
     * @param issues  collector; unknown references append an issue and are removed
     * @return expanded value
     */
    public static String expand(String raw, String path, List<ValidationIssue> issues) {
        if (raw == null || raw.indexOf("${") < 0) {
            return raw;
        }
        Matcher m = REF.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String fallback = m.group(2);
            String value = System.getenv(name);
            if (value == null) {
                if (fallback != null) {
                    value = fallback;
                } else {
                    issues.add(new ValidationIssue(path,
                            "references undefined environment variable " + name + " and has no fallback"));
                    value = "";
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
