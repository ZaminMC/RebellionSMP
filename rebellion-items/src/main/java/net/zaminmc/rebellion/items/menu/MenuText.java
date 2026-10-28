package net.zaminmc.rebellion.items.menu;

import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure menu-side placeholder substitution (master prompt §9/§10).
 *
 * <p>Menu text recognizes exactly one token shape:
 * {@code %rebellion_<params>%} — the shared {@code rebellion} namespace the
 * PlaceholderAPI expansion also serves. The resolver receives {@code params}
 * (everything between the {@code rebellion_} prefix and the closing
 * {@code %}), matching the {@code PlaceholderContributor} contract. Tokens
 * of other plugins or namespaces are deliberately NOT touched: this engine
 * is not a generic PAPI renderer and never grows one.</p>
 *
 * <p>A resolver answer of {@code null} leaves the token as visible raw text
 * (the operator sees their own typo or an absent module, instead of a
 * silently blank line — the same convention PlaceholderAPI text handling
 * uses). Any non-null answer, including an empty string, is substituted
 * verbatim; {@link Matcher#quoteReplacement} guards literal {@code $} and
 * backslashes in values.</p>
 */
public final class MenuText {

    /** Rebellion-namespace tokens; params charset mirrors contributor params. */
    private static final Pattern TOKEN =
            Pattern.compile("%rebellion_([a-zA-Z0-9_\\-]+)%");

    private MenuText() {
    }

    /**
     * Substitutes every {@code %rebellion_<params>%} token in {@code raw}
     * using {@code resolver}. Never returns null for non-null input; a
     * string without tokens returns the input unchanged.
     */
    public static String placeholders(String raw, UnaryOperator<String> resolver) {
        if (raw == null || raw.isEmpty() || !raw.contains("%rebellion_")) {
            return raw;
        }
        Matcher matcher = TOKEN.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String answer = resolver.apply(matcher.group(1));
            String replacement = answer == null
                    ? matcher.group(0) // unresolved: the token stays visible
                    : Matcher.quoteReplacement(answer);
            matcher.appendReplacement(out, replacement);
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
