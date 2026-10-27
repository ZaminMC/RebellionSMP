package net.zaminmc.rebellion.teams.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Chat routing policy: team recipient set + proximity radius filtering. */
final class ChatRouterTest {

    private final UUID sender = UUID.randomUUID();
    private final UUID teammate = UUID.randomUUID();

    @Test
    void teamChannelIncludesSenderAndTeammatesOnly() {
        Set<UUID> recipients = ChatRouter.teamRecipients(sender, Set.of(sender, teammate));
        assertEquals(Set.of(sender, teammate), recipients);
        // Team chat is not world-scoped; empty teammates still reach sender.
        assertEquals(Set.of(sender), ChatRouter.teamRecipients(sender, Set.of()));
    }

    @Test
    void proximityChannelFiltersByWorldAndRadius() {
        UUID near = UUID.randomUUID();
        UUID far = UUID.randomUUID();
        UUID otherWorld = UUID.randomUUID();
        List<ChatRouter.Candidate> candidates = List.of(
                new ChatRouter.Candidate(sender, "earth", 0, 0),
                new ChatRouter.Candidate(near, "earth", 30, 40),     // 50 blocks
                new ChatRouter.Candidate(far, "earth", 300, 400),   // 500 blocks
                new ChatRouter.Candidate(otherWorld, "nether", 0, 0));

        Set<UUID> recipients = ChatRouter.proximityRecipients(sender, candidates, 100.0);
        assertTrue(recipients.contains(sender));
        assertTrue(recipients.contains(near));
        assertTrue(!recipients.contains(far));
        assertTrue(!recipients.contains(otherWorld),
                "proximity chat never crosses worlds");
    }

    @Test
    void radiusBoundaryIsInclusiveHorizontally() {
        UUID exactlyAtEdge = UUID.randomUUID();
        List<ChatRouter.Candidate> candidates = List.of(
                new ChatRouter.Candidate(sender, "w", 0, 0),
                new ChatRouter.Candidate(exactlyAtEdge, "w", 60, 80)); // exactly 100
        assertTrue(ChatRouter.proximityRecipients(sender, candidates, 100.0)
                .contains(exactlyAtEdge));
    }

    @Test
    void missingSenderFallsBackToSenderOnly() {
        UUID someone = UUID.randomUUID();
        Set<UUID> recipients = ChatRouter.proximityRecipients(sender,
                List.of(new ChatRouter.Candidate(someone, "w", 0, 0)), 100.0);
        assertEquals(Set.of(sender), recipients);
    }
}
