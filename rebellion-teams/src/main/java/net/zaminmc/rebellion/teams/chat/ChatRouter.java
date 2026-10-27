package net.zaminmc.rebellion.teams.chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Server-free chat routing policy (ADR-0006). The Bukkit adapter supplies the
 * online-player candidates; this class decides the recipient set per channel.
 * Global chat is not routed here — it keeps vanilla behavior.
 */
public final class ChatRouter {

    /** One online player: identity plus world-scoped position. */
    public record Candidate(UUID player, String world, double x, double z) {
    }

    private ChatRouter() {
    }

    /**
     * Team channel: recipients are the sender's online teammates (including
     * the sender). Team chat is not world-scoped.
     */
    public static Set<UUID> teamRecipients(UUID sender, Set<UUID> onlineTeammates) {
        Set<UUID> out = new HashSet<>(onlineTeammates);
        out.add(sender);
        return out;
    }

    /**
     * Proximity channel: same world and horizontal (x/z) distance within
     * {@code radius}. The sender is always included; other worlds are never
     * merged; no vertical component (same standing crowd), matching the
     * ward-distance convention used by Phase 4.
     */
    public static Set<UUID> proximityRecipients(UUID sender, List<Candidate> candidates,
            double radius) {
        Candidate origin = null;
        for (Candidate c : candidates) {
            if (c.player().equals(sender)) {
                origin = c;
                break;
            }
        }
        if (origin == null) {
            return Set.of(sender);
        }
        double radiusSq = radius * radius;
        List<UUID> out = new ArrayList<>();
        for (Candidate c : candidates) {
            if (!c.world().equals(origin.world())) {
                continue;
            }
            double dx = c.x() - origin.x();
            double dz = c.z() - origin.z();
            if (dx * dx + dz * dz <= radiusSq) {
                out.add(c.player());
            }
        }
        if (out.isEmpty()) {
            return Set.of(sender);
        }
        return Set.copyOf(out);
    }
}
