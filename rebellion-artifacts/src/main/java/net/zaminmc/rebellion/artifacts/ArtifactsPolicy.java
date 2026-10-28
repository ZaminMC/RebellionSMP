package net.zaminmc.rebellion.artifacts;

/**
 * Fixed artifact policy constants (master prompt §5.5, ADR-0013). Exactly
 * three dimension artifacts exist; the set is closed and derives from
 * {@link ArtifactKey#values()} so the schema, the seed path, the Rebellion
 * qualification check and the tests all share one constant.
 */
public final class ArtifactsPolicy {

    private ArtifactsPolicy() {
    }

    /**
     * The number of dimension artifacts — one per dimension, exactly three,
     * forever. Not configurable: a fourth artifact cannot be minted by any
     * gameplay path, and a table that does not hold exactly this many rows
     * fails the module load loudly.
     */
    public static final int ARTIFACT_COUNT = ArtifactKey.values().length;
}
