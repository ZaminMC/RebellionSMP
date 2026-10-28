package net.zaminmc.rebellion.core.messages;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.core.config.ValidationIssue;
import net.zaminmc.rebellion.core.config.YamlFileIO;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Core's typed view over {@code messages.yml}.
 *
 * <p>Since ADR-0012 the loading, strict MiniMessage validation and rendering
 * live in the shared API {@link MessageCatalog} (one implementation for Core
 * and every feature module); this class keeps Core's public surface —
 * {@link ValidationIssue} reporting and Core's precise {@link YamlFileIO}
 * file errors — unchanged for its call sites.</p>
 *
 * <p><strong>Well-formedness contract:</strong> every MiniMessage tag in
 * message files must be explicitly closed (strict instance, verified against
 * adventure-text-minimessage 5.2.0). Unclosed tags are the most common
 * copy/paste editing mistake, so loading rejects them with a precise path.
 * Placeholder arguments like {@code <version>} are exempt: the validation
 * instance accepts any identifier the standard tag set does not own, because
 * the real resolvers are supplied at render time by calling code.</p>
 */
public final class CoreMessages {

    private final MessageCatalog catalog;

    private CoreMessages(MessageCatalog catalog) {
        this.catalog = catalog;
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
        MessageCatalog.LoadResult merged = MessageCatalog.merge(user, defaults);
        return new LoadResult(new CoreMessages(merged.catalog()),
                toValidationIssues(merged.issues()));
    }

    /** Parses a candidate snapshot without replacing the active one. */
    public static LoadResult parse(YamlConfiguration candidate) {
        MessageCatalog.LoadResult result = MessageCatalog.parse(candidate);
        return new LoadResult(result.catalog() == null ? null : new CoreMessages(result.catalog()),
                toValidationIssues(result.issues()));
    }

    private static List<ValidationIssue> toValidationIssues(List<MessageCatalog.Issue> issues) {
        List<ValidationIssue> mapped = new ArrayList<>(issues.size());
        for (MessageCatalog.Issue issue : issues) {
            mapped.add(new ValidationIssue(issue.key(), issue.problem()));
        }
        return mapped;
    }

    /** Renders a message without prefix. */
    public Component raw(String key, TagResolver... resolvers) {
        return catalog.raw(key, resolvers);
    }

    /** Renders a message with the configured prefix. */
    public Component msg(String key, TagResolver... resolvers) {
        return catalog.msg(key, resolvers);
    }

    public Component prefix() {
        return catalog.prefix();
    }

    public String template(String key) {
        return catalog.template(key);
    }
}
