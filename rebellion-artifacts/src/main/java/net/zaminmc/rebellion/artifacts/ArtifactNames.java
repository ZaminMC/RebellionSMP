package net.zaminmc.rebellion.artifacts;

import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.NotNull;

/**
 * Localizable artifact naming (ADR-0012 discipline): every player-facing
 * surface renders artifact names through the message catalog — never
 * hard-coded English, never parsed player text. The per-key short name is
 * pinned by ArtifactsMessagesTest together with the rest of the catalog.
 */
public final class ArtifactNames {

    private ArtifactNames() {
    }

    /** @return the localized short name of one artifact ("the Nether Star"). */
    public static @NotNull Component shortName(@NotNull MessageCatalog messages,
            @NotNull ArtifactKey key, @NotNull TagResolver... resolvers) {
        return messages.raw("artifact." + key.configSegment() + ".short", resolvers);
    }

    /** @return the localized item display name of one artifact. */
    public static @NotNull Component itemName(@NotNull MessageCatalog messages,
            @NotNull ArtifactKey key, @NotNull TagResolver... resolvers) {
        return messages.raw("item." + key.configSegment() + ".name", resolvers);
    }
}
