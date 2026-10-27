package net.zaminmc.rebellion.wards.detect;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Whether a player is currently masked from ward detection
 * (countermeasure contract, master prompt §5.2).
 *
 * <p>The detector consults this at decision time only — never caches the
 * answer — so implementations may combine sources freely:</p>
 * <ul>
 *   <li>Timed masking granted by admins now (later: counter-rituals in the
 *       RPG phase), and</li>
 *   <li>held-item masking (configured material in the main hand) resolved
 *       through a live {@code Player} when on the server path.</li>
 * </ul>
 *
 * <p>Semantics (tested in {@code WardDetectorTest}): a masked player never
 * triggers a new ENTER alert; an ENTER committed during the dwell window is
 * suppressed if masking starts before the commit. Masking never undoes an
 * already-delivered alert, and it never removes the player's inside state —
 * a sentry that never saw the entry stays ignorant.</p>
 */
public interface MaskingResolver {

    /** @param nowMs the sweep's logical clock, for timed grants */
    boolean masked(@Nullable UUID player, long nowMs);
}
