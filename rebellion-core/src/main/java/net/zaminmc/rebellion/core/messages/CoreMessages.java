package net.zaminmc.rebellion.core.messages;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.zaminmc.rebellion.core.config.ValidationIssue;
import net.zaminmc.rebellion.core.config.YamlFileIO;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * MiniMessage-based access to {@code messages.yml}.
 *
 * <p>Missing keys fall back to the bundled default at parse time so a partially
 * edited file can never render empty output; the fallback is reported as a
 * validation issue instead. Reload swaps the whole immutable snapshot.</p>
 *
 * <p><strong>Well-formedness contract:</strong> every MiniMessage tag in message
 * files must be explicitly closed (verified with a strict MiniMessage instance).
 * Unclosed tags are idiomatic in hand-written MiniMessage but are the most common
 * copy/paste editing mistake, so this loader rejects them with a precise path.
 * Placeholder arguments like {@code <version>} are exempt: a catch-all resolver
 * accepts any identifier not owned by the standard tag set, because the real
 * resolvers are supplied at render time by calling code.</p>
 */
public final class CoreMessages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /**
     * Strict instance used to prove template well-formedness at load time.
     * It accepts bare runtime placeholders through a catch-all resolver that
     * never shadows real MiniMessage tags.
     */
    private static final MiniMessage VALIDATION = MiniMessage.builder()
            .strict(true)
            .tags(TagResolver.resolver(TagResolver.standard(), new TagResolver() {
                @Override
                public net.kyori.adventure.text.minimessage.tag.Tag resolve(
                        String name, net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue args,
                        net.kyori.adventure.text.minimessage.Context ctx) {
                    return net.kyori.adventure.text.minimessage.tag.Tag
                            .selfClosingInserting(net.kyori.adventure.text.Component.text("<" + name + ">"));
                }

                @Override
                public boolean has(String name) {
                    return !TagResolver.standard().has(name)
                            && name.matches("[a-z0-9_-]+");
                }
            }))
            .build();

    private final Map<String, String> messages;
    private final Component prefix;

    private CoreMessages(Map<String, String> messages) {
        this.messages = messages;
        this.prefix = MINI.deserialize(messages.getOrDefault("prefix", ""));
    }

    public record LoadResult(CoreMessages messages, List<ValidationIssue> issues) {
        public boolean valid() {
            return issues.isEmpty();
        }
    }

    public static LoadResult load(Path file, Logger logger) throws YamlFileIO.InvalidConfigException {
        YamlConfiguration user = YamlFileIO.loadOrCreate(file, "messages.yml", logger);
        YamlConfiguration defaults = new YamlConfiguration();
        try (var in = CoreMessages.class.getResourceAsStream("/messages.yml")) {
            if (in != null) {
                defaults.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            logger.warning("Bundled messages.yml defaults unreadable: " + e.getMessage());
        }

        List<ValidationIssue> issues = new ArrayList<>();
        Map<String, String> merged = new HashMap<>();
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                continue;
            }
            String value = user.getString(key);
            if (value == null) {
                issues.add(new ValidationIssue(key, "missing in messages.yml; using bundled default"));
                value = defaults.getString(key, key);
            }
            merged.put(key, value);
        }
        // Keys that exist only in the user file are kept: admins may add entries
        // for modules not bundled here. They are not validated.
        for (String key : user.getKeys(true)) {
            if (!user.isConfigurationSection(key) && !merged.containsKey(key)) {
                merged.put(key, user.getString(key, key));
            }
        }
        return new LoadResult(new CoreMessages(merged), issues);
    }

    /** Parses a candidate snapshot without replacing the active one. */
    public static LoadResult parse(YamlConfiguration candidate) {
        List<ValidationIssue> issues = new ArrayList<>();
        Map<String, String> map = new HashMap<>();
        for (String key : candidate.getKeys(true)) {
            if (!candidate.isConfigurationSection(key)) {
                String value = candidate.getString(key);
                if (value == null) {
                    issues.add(new ValidationIssue(key, "has no text value"));
                    continue;
                }
                // Prove the template is well-formed before swapping it in.
                // Strict mode throws on unclosed tags (verified against
                // adventure-text-minimessage 5.2.0); malformed tag arguments
                // still degrade to literal text and are documented as such.
                try {
                    VALIDATION.deserialize(value);
                } catch (Exception e) {
                    String reason = e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage().split("\n")[0];
                    issues.add(new ValidationIssue(key, "invalid MiniMessage: " + reason));
                    continue;
                }
                map.put(key, value);
            }
        }
        if (!issues.isEmpty()) {
            return new LoadResult(null, issues);
        }
        return new LoadResult(new CoreMessages(map), issues);
    }

    /** Renders a message without prefix. */
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

    public Component prefix() {
        return prefix;
    }

    public String template(String key) {
        return messages.get(key);
    }
}
