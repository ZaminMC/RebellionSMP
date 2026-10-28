package net.zaminmc.rebellion.artifacts.command;

import java.util.Locale;
import net.zaminmc.rebellion.api.placeholder.PlaceholderContributor;
import net.zaminmc.rebellion.artifacts.Artifact;
import net.zaminmc.rebellion.artifacts.ArtifactKey;
import net.zaminmc.rebellion.artifacts.ArtifactService;
import net.zaminmc.rebellion.artifacts.RebellionEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * RebellionArtifacts' contribution to the shared {@code rebellion}
 * expansion (master prompt §10):
 *
 * <ul>
 *   <li>{@code %rebellion_artifact_state_<key>%} — ORIGIN/HELD/LOST/
 *       RETURNING/SPENT of one artifact (overworld/nether/end).</li>
 *   <li>{@code %rebellion_artifact_holder_<key>%} — the recorded holder
 *       UUID, or empty when unheld. The holder is public gameplay state
 *       (possession is broadcast through the flows); the protected origin
 *       coordinates are NOT exposed (§10 privacy).</li>
 *   <li>{@code %rebellion_artifact_event_active%} — true/false.</li>
 * </ul>
 *
 * <p>Pure in-memory mirror reads only — never a database query per
 * placeholder request, and never any origin/secret data.</p>
 */
public final class ArtifactsPlaceholders implements PlaceholderContributor {

    private final ArtifactService service;

    public ArtifactsPlaceholders(@NotNull ArtifactService service) {
        this.service = service;
    }

    @Override
    public @Nullable String resolve(@NotNull String params) {
        String lowered = params.toLowerCase(Locale.ROOT);
        if (lowered.startsWith("artifact_state_")) {
            Artifact artifact = bySegment(lowered.substring("artifact_state_".length()));
            return artifact == null ? null : artifact.state().name();
        }
        if (lowered.startsWith("artifact_holder_")) {
            Artifact artifact = bySegment(
                    lowered.substring("artifact_holder_".length()));
            if (artifact == null) {
                return null;
            }
            return artifact.holder() == null ? "" : artifact.holder().toString();
        }
        return switch (lowered) {
            case "artifact_event_active" -> String.valueOf(
                    service.activeEvent().isPresent());
            default -> null;
        };
    }

    private @Nullable Artifact bySegment(@NotNull String segment) {
        ArtifactKey key;
        try {
            key = ArtifactKey.parse(segment);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return service.byKey(key).orElse(null);
    }
}
