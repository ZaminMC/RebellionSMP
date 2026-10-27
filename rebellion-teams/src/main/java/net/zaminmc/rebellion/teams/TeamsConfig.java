package net.zaminmc.rebellion.teams;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Validated snapshot of RebellionTeams' {@code config.yml}. Immutable; a
 * reload swaps the whole record atomically.
 */
public record TeamsConfig(
        int maxMembers,
        int maxPendingInvites,
        int inviteExpirySeconds,
        int createCooldownSeconds,
        int renameCooldownSeconds,
        int inviteSendIntervalSeconds,
        boolean protectAllies,
        double proximityRadius,
        boolean proximityEnabled,
        String defaultChannel,
        int messageIntervalSeconds) {

    /** Parses + validates; collects every issue instead of failing on the first. */
    public record LoadResult(TeamsConfig config, List<String> issues) {
        public boolean valid() {
            return config != null && issues.isEmpty();
        }
    }

    public static TeamsConfig load(YamlConfiguration yaml) {
        var result = loadResult(yaml);
        if (!result.valid()) {
            throw new IllegalStateException("config invalid: " + result.issues());
        }
        return result.config();
    }

    /** @return candidate snapshot (null when invalid) plus every issue found. */
    public static LoadResult loadResult(YamlConfiguration yaml) {
        List<String> issues = new ArrayList<>();

        int maxMembers = yaml.getInt("teams.max-members", 8);
        if (maxMembers < 2 || maxMembers > 100) {
            issues.add("teams.max-members: must be between 2 and 100 (got " + maxMembers + ")");
        }

        int maxPending = yaml.getInt("teams.max-pending-invites", 3);
        if (maxPending < 1 || maxPending > 20) {
            issues.add("teams.max-pending-invites: must be between 1 and 20 (got " + maxPending + ")");
        }

        int inviteExpiry = yaml.getInt("teams.invite-expiry-seconds", 86400);
        if (inviteExpiry < 60 || inviteExpiry > 2_592_000) {
            issues.add("teams.invite-expiry-seconds: must be between 60 and 2592000 (got "
                    + inviteExpiry + ")");
        }

        int createCooldown = yaml.getInt("teams.create-cooldown-seconds", 300);
        if (createCooldown < 0 || createCooldown > 86400) {
            issues.add("teams.create-cooldown-seconds: must be between 0 and 86400 (got "
                    + createCooldown + ")");
        }

        int renameCooldown = yaml.getInt("teams.rename-cooldown-seconds", 3600);
        if (renameCooldown < 0 || renameCooldown > 604800) {
            issues.add("teams.rename-cooldown-seconds: must be between 0 and 604800 (got "
                    + renameCooldown + ")");
        }

        int inviteInterval = yaml.getInt("teams.invite-send-interval-seconds", 30);
        if (inviteInterval < 0 || inviteInterval > 3600) {
            issues.add("teams.invite-send-interval-seconds: must be between 0 and 3600 (got "
                    + inviteInterval + ")");
        }

        boolean protectAllies = yaml.getBoolean("teams.protect-allies", false);

        double radius = yaml.getDouble("chat.proximity-radius", 100.0);
        if (radius < 8.0 || radius > 2000.0) {
            issues.add("chat.proximity-radius: must be between 8.0 and 2000.0 (got " + radius + ")");
        }

        boolean proximityEnabled = yaml.getBoolean("chat.proximity-enabled", true);

        String defaultChannel = yaml.getString("chat.default-channel", "global");
        defaultChannel = defaultChannel == null ? "global"
                : defaultChannel.toLowerCase(Locale.ROOT);
        if (!defaultChannel.equals("global") && !defaultChannel.equals("team")
                && !defaultChannel.equals("proximity")) {
            issues.add("chat.default-channel: expected global, team or proximity (got '"
                    + defaultChannel + "')");
            defaultChannel = "global";
        }
        if ("proximity".equals(defaultChannel) && !proximityEnabled) {
            issues.add("chat.default-channel: cannot default to proximity while "
                    + "chat.proximity-enabled is false");
        }

        int messageInterval = yaml.getInt("chat.message-interval-seconds", 1);
        if (messageInterval < 1 || messageInterval > 30) {
            issues.add("chat.message-interval-seconds: must be between 1 and 30 (got "
                    + messageInterval + ")");
        }

        TeamsConfig config = issues.isEmpty() ? new TeamsConfig(maxMembers, maxPending,
                inviteExpiry, createCooldown, renameCooldown, inviteInterval, protectAllies,
                radius, proximityEnabled, defaultChannel, messageInterval) : null;
        return new LoadResult(config, List.copyOf(issues));
    }
}
