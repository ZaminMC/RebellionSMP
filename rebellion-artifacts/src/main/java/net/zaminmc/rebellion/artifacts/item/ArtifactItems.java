package net.zaminmc.rebellion.artifacts.item;

import net.zaminmc.rebellion.artifacts.Artifact;
import net.zaminmc.rebellion.artifacts.ArtifactKey;
import net.zaminmc.rebellion.artifacts.ArtifactsConfig;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.Builder;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Physical artifact identity and delivery (ADR-0015 §2, the EggItems
 * discipline). The artifact item is the configured per-dimension material
 * stamped in its PDC with the immutable {@code artifact_id} plus the live
 * {@code instance} UUID. The instance stamp is the anti-clone contract:
 * every possession change supersedes it, and the service resolves only the
 * currently-live stamp of a presentable row, so cloned or stale copies are
 * structurally inert — they can never move a row or qualify a trigger.
 *
 * <p>Identity keys live under this module's namespace. Display name and
 * lore render through the ADR-0012 message catalog (never parsed player
 * text), so operators re-skin items without touching identity.</p>
 */
public final class ArtifactItems {

    /** PDC key of the immutable artifact UUID on a physical artifact. */
    public static final String ARTIFACT_KEY = "artifact";
    /** PDC key of the live instance stamp on a physical artifact. */
    public static final String INSTANCE_KEY = "artifact_instance";

    private final NamespacedKey artifactIdKey;
    private final NamespacedKey instanceKey;

    public ArtifactItems(@NotNull Plugin plugin) {
        this.artifactIdKey = new NamespacedKey(plugin, ARTIFACT_KEY);
        this.instanceKey = new NamespacedKey(plugin, INSTANCE_KEY);
    }

    /** @return this module's artifact-id PDC key (admin forensics/tests). */
    public @NotNull NamespacedKey artifactIdKey() {
        return artifactIdKey;
    }

    /** @return this module's live-instance PDC key. */
    public @NotNull NamespacedKey instanceKey() {
        return instanceKey;
    }

    /**
     * Builds the physical artifact item for a row's current live stamp.
     * Only ever called with the row read in the same tick as the CAS that
     * follows (capture/found/transfer/route) or for the origin presentation
     * — a stack built from a superseded stamp is inert by design.
     */
    public @NotNull ItemStack build(@NotNull Artifact artifact,
            @NotNull ArtifactsConfig config, @NotNull MessageCatalog messages,
            @NotNull TagResolver... extra) {
        ItemStack stack = new ItemStack(config.itemMaterial(artifact.artifactKey()));
        stamp(stack, artifact);
        ItemMeta meta = stack.getItemMeta();
        Builder resolvers = TagResolver.builder().resolvers(extra);
        meta.displayName(messages.raw("item." + artifact.artifactKey().configSegment()
                + ".name", resolvers.build()));
        meta.lore(java.util.List.of(
                messages.raw("item." + artifact.artifactKey().configSegment() + ".lore",
                        resolvers.build())));
        stack.setItemMeta(meta);
        return stack;
    }

    /** Stamps an existing stack with the row's identity + live instance. */
    public void stamp(@NotNull ItemStack stack, @NotNull Artifact artifact) {
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(artifactIdKey, PersistentDataType.STRING,
                artifact.artifactId().toString());
        pdc.set(instanceKey, PersistentDataType.STRING,
                artifact.instance().toString());
        stack.setItemMeta(meta);
    }

    /** Reads the stamped identity of a physical artifact stack, if any. */
    public @Nullable StampedIdentity read(@Nullable ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
        String artifact = pdc.get(artifactIdKey, PersistentDataType.STRING);
        String instance = pdc.get(instanceKey, PersistentDataType.STRING);
        if (artifact == null || instance == null) {
            return null;
        }
        try {
            return new StampedIdentity(java.util.UUID.fromString(artifact),
                    java.util.UUID.fromString(instance));
        } catch (IllegalArgumentException e) {
            // Forged/garbage stamps never authorize anything (fail closed).
            return null;
        }
    }

    /** PDC identity read from a physical artifact. */
    public record StampedIdentity(@NotNull java.util.UUID artifactId,
            @NotNull java.util.UUID instance) {
    }
}
