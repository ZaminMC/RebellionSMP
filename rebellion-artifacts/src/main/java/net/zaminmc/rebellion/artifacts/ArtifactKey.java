package net.zaminmc.rebellion.artifacts;

import java.util.Locale;
import org.jetbrains.annotations.NotNull;

/**
 * The stable identity of a dimension artifact (ADR-0013): exactly three
 * exist, one anchored to each dimension, and the key is the registry row's
 * PRIMARY KEY. It never changes for the life of the server — the
 * server-issued {@code artifactId} UUID it carries is what the physical item
 * family is stamped with.
 *
 * <p>Themes (master prompt §5.5):</p>
 * <ul>
 *   <li>{@link #OVERWORLD} — life / dominion.</li>
 *   <li>{@link #NETHER} — fire / destruction / war.</li>
 *   <li>{@link #END} — void / knowledge / dimensional influence.</li>
 * </ul>
 *
 * <p>The set is closed on purpose: {@link ArtifactsPolicy#ARTIFACT_COUNT}
 * derives from {@link #values()}, and the seed path is a loop over the enum,
 * so "exactly three" is the same constant in schema, service, config and
 * tests.</p>
 */
public enum ArtifactKey {
    OVERWORLD,
    NETHER,
    END;

    /** Parses the stored/config representation; unknown values fail loudly. */
    public static @NotNull ArtifactKey parse(@NotNull String raw) {
        return ArtifactKey.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }

    /** @return the lowercase config path segment for this artifact. */
    public @NotNull String configSegment() {
        return name().toLowerCase(Locale.ROOT);
    }
}
