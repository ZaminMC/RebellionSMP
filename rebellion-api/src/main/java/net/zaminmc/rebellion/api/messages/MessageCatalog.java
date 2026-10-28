package net.zaminmc.rebellion.api.messages;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * MiniMessage-based message catalog for {@code messages.yml} — the one shared
 * implementation used by Core (through its {@code CoreMessages} adapter) and
 * by every feature module directly.
 *
 * <p><strong>Why this lives in the API:</strong> feature modules have a
 * compile edge only on RebellionAPI (Core is a runtime plugin dependency), so
 * the API is the only place a single runtime class identity can be shared —
 * the same reasoning that placed {@code TeamAffiliationService} there. It
 * contains no game logic: loading, validating and rendering message files is
 * configuration infrastructure.</p>
 *
 * <p><strong>Loading contract</strong> ({@link #load}): the bundled default
 * resource is copied into the module's data folder on first boot and is never
 * written over admin edits afterwards. User values win over bundled defaults;
 * keys missing from the user file fall back to the bundled default and are
 * reported as issues instead of rendering empty output. A user-edited template
 * that fails strict validation falls back to the bundled default at load time
 * (boot must come up); the same defect in a reload candidate rejects the whole
 * reload instead ({@link #parse}) so a half-edited file never half-applies.
 * Keys that exist only in the user file are kept unvalidated — admins may add
 * entries this build does not know about.</p>
 *
 * <p><strong>Well-formedness contract:</strong> every MiniMessage tag in a
 * message file must be explicitly closed (verified with a strict MiniMessage
 * instance against adventure-text-minimessage 5.2.0 — strict mode throws on
 * unclosed tags). Runtime placeholders like {@code <detail>} are exempt: a
 * catch-all resolver accepts any identifier the standard tag set does not own,
 * because the real resolvers are supplied at render time by calling code.
 * Literal angle brackets in templates (usage strings like
 * {@code /team invite \<player>}) escape only the opening bracket with a
 * backslash; MiniMessage has no {@code \>} escape (verified against
 * adventure-text-minimessage 5.2.0 — a backslash before {@code >} stays
 * literal text), and a bare {@code >} outside a tag is plain text anyway.</p>
 */
public final class MessageCatalog {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /**
     * Strict instance used to prove template well-formedness at load and
     * reload time. It accepts bare runtime placeholders through a catch-all
     * resolver that never shadows real MiniMessage tags.
     */
    private static final MiniMessage VALIDATION = MiniMessage.builder()
            .strict(true)
            .tags(TagResolver.resolver(TagResolver.standard(), new TagResolver() {
                @Override
                public net.kyori.adventure.text.minimessage.tag.Tag resolve(
                        String name, net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue args,
                        net.kyori.adventure.text.minimessage.Context ctx) {
                    return net.kyori.adventure.text.minimessage.tag.Tag
                            .selfClosingInserting(Component.text("<" + name + ">"));
                }

                @Override
                public boolean has(String name) {
                    return !TagResolver.standard().has(name)
                            && name.matches("[a-z0-9_-]+");
                }
            }))
            .build();

    /** Resource names are flat file names only — no path separators, ever. */
    private static final Pattern RESOURCE_NAME = Pattern.compile("[a-z0-9_.-]{1,64}");

    /** One validation problem: the message key and a precise explanation. */
    public record Issue(String key, String problem) {
        @Override
        public String toString() {
            return key + ": " + problem;
        }
    }

    /** Load outcome; {@code catalog} is null exactly when a parse was rejected. */
    public record LoadResult(MessageCatalog catalog, List<Issue> issues) {
        public boolean valid() {
            return issues.isEmpty();
        }
    }

    /** Thrown when the message file (or its bundled default) is unreadable. */
    public static final class MessageFileException extends Exception {
        public MessageFileException(String message) {
            super(message);
        }

        public MessageFileException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Map<String, String> messages;
    private final Component prefix;

    private MessageCatalog(Map<String, String> messages) {
        this.messages = messages;
        this.prefix = MINI.deserialize(messages.getOrDefault("prefix", ""));
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    /**
     * Module entry point: ensures {@code <dataFolder>/<resource>} exists
     * (copying the bundled default on first boot), then loads and merges it.
     *
     * @param file          target file, e.g. {@code dataFolder/messages.yml}
     * @param resource      bundled default resource name (flat, e.g. "messages.yml")
     * @param resourceOwner class whose classloader carries the bundled resource
     *                      (the module's own plugin class — never this class,
     *                      which would resolve the API's classpath instead)
     * @param logger        receives the "created default file" notice
     * @throws MessageFileException when the file or bundled default is unreadable
     */
    public static LoadResult load(Path file, String resource, Class<?> resourceOwner, Logger logger)
            throws MessageFileException {
        if (!RESOURCE_NAME.matcher(resource).matches()) {
            throw new MessageFileException("illegal resource name '" + resource + "'");
        }
        byte[] bundled = readBundled(resource, resourceOwner);
        if (Files.notExists(file)) {
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, bundled);
                logger.info("Created default " + file.getFileName() + ".");
            } catch (IOException e) {
                throw new MessageFileException(
                        "Could not create " + file + ": " + e.getMessage(), e);
            }
        }
        YamlConfiguration user = new YamlConfiguration();
        try {
            user.load(file.toFile());
        } catch (IOException e) {
            throw new MessageFileException("Cannot read " + file + ": " + e.getMessage(), e);
        } catch (InvalidConfigurationException e) {
            throw new MessageFileException(
                    "Invalid YAML in " + file + " (line " + describeLine(e.getMessage()) + ")", e);
        }
        YamlConfiguration defaults = new YamlConfiguration();
        try {
            defaults.loadFromString(new String(bundled, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException e) {
            throw new MessageFileException(
                    "Bundled default " + resource + " is invalid: " + e.getMessage(), e);
        }
        return merge(user, defaults);
    }

    /**
     * Pure merge for callers that own their own file IO (Core's adapter keeps
     * its precise {@code YamlFileIO} error reporting): user values win, missing
     * keys fall back to the bundled default and are reported, and a user value
     * that fails strict validation falls back to the bundled default and is
     * reported. User-only keys are kept unvalidated.
     */
    public static LoadResult merge(YamlConfiguration user, YamlConfiguration defaults) {
        List<Issue> issues = new ArrayList<>();
        Map<String, String> merged = new HashMap<>();
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                continue;
            }
            String userValue = user.getString(key);
            if (userValue == null) {
                issues.add(new Issue(key, "missing in messages.yml; using bundled default"));
                merged.put(key, defaults.getString(key, key));
                continue;
            }
            String problem = validationProblem(userValue);
            if (problem == null) {
                merged.put(key, userValue);
            } else {
                issues.add(new Issue(key, problem + "; using bundled default"));
                merged.put(key, defaults.getString(key, key));
            }
        }
        for (String key : user.getKeys(true)) {
            if (!user.isConfigurationSection(key) && !merged.containsKey(key)) {
                merged.put(key, user.getString(key, key));
            }
        }
        return new LoadResult(new MessageCatalog(merged), issues);
    }

    /**
     * Reload path: parses a candidate snapshot <em>without</em> replacing the
     * active one. Any invalid template rejects the whole candidate (null
     * catalog) so a reload can never half-apply.
     */
    public static LoadResult parse(YamlConfiguration candidate) {
        List<Issue> issues = new ArrayList<>();
        Map<String, String> map = new HashMap<>();
        for (String key : candidate.getKeys(true)) {
            if (!candidate.isConfigurationSection(key)) {
                String value = candidate.getString(key);
                if (value == null) {
                    issues.add(new Issue(key, "has no text value"));
                    continue;
                }
                String problem = validationProblem(value);
                if (problem != null) {
                    issues.add(new Issue(key, problem));
                    continue;
                }
                map.put(key, value);
            }
        }
        if (!issues.isEmpty()) {
            return new LoadResult(null, issues);
        }
        return new LoadResult(new MessageCatalog(map), issues);
    }

    /** @return the strict-validation failure for this template, or null when well-formed. */
    private static String validationProblem(String template) {
        try {
            VALIDATION.deserialize(template);
            return null;
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage().split("\n")[0];
            return "invalid MiniMessage: " + reason;
        }
    }

    private static byte[] readBundled(String resource, Class<?> owner) throws MessageFileException {
        try (InputStream in = owner.getResourceAsStream("/" + resource)) {
            if (in == null) {
                throw new MessageFileException(
                        "Bundled default resource '" + resource + "' is missing from "
                                + owner.getName() + "'s jar");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new MessageFileException(
                    "Bundled default resource '" + resource + "' is unreadable: " + e.getMessage(), e);
        }
    }

    private static String describeLine(String message) {
        if (message == null) {
            return "?";
        }
        java.util.regex.Matcher m = Pattern.compile("line (\\d+)").matcher(message);
        return m.find() ? m.group(1) : "?";
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /** Renders a message without prefix. Unknown keys render as their own name. */
    public Component raw(String key, TagResolver... resolvers) {
        String template = messages.get(key);
        if (template == null) {
            return Component.text(key);
        }
        return MINI.deserialize(template, resolvers);
    }

    /** Renders a message with the configured prefix. */
    public Component msg(String key, TagResolver... resolvers) {
        return prefix.append(raw(key, resolvers));
    }

    /** @return the configured prefix component (empty when unset). */
    public Component prefix() {
        return prefix;
    }

    /** @return the raw template string, or null when the key is unknown. */
    public String template(String key) {
        return messages.get(key);
    }
}
