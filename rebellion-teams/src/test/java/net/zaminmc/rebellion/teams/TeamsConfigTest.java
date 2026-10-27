package net.zaminmc.rebellion.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Bundled teams config validity + invalid-value issue reporting. All issues
 * are collected, and every reject keeps the snapshot null (atomic reload).
 */
final class TeamsConfigTest {

    private static YamlConfiguration bundled() throws Exception {
        try (var in = TeamsConfigTest.class.getResourceAsStream("/config.yml")) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return yaml;
        }
    }

    private static YamlConfiguration fromString(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    @Test
    void bundledConfigIsValid() throws Exception {
        var result = TeamsConfig.loadResult(bundled());
        assertTrue(result.valid(), () -> result.issues().toString());
        assertTrue(result.config().proximityEnabled());
        assertEquals("global", result.config().defaultChannel());
    }

    @Test
    void collectsEveryIssueInsteadOfFailingOnTheFirst() throws Exception {
        var result = TeamsConfig.loadResult(fromString("""
                teams:
                  max-members: 1
                  max-pending-invites: 99
                  invite-expiry-seconds: 1
                chat:
                  proximity-radius: 1.0
                  default-channel: yell
                  message-interval-seconds: 0
                """));
        assertTrue(result.config() == null);
        assertTrue(result.issues().size() >= 6, () -> result.issues().toString());
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("max-members")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("invite-expiry-seconds")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("proximity-radius")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("default-channel")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("message-interval-seconds")));
    }

    @Test
    void proximityDefaultChannelNeedsTheChannelEnabled() throws Exception {
        var result = TeamsConfig.loadResult(fromString("""
                chat:
                  proximity-enabled: false
                  default-channel: proximity
                """));
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.contains("cannot default to proximity")));
    }
}
