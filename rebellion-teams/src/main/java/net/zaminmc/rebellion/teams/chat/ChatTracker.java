package net.zaminmc.rebellion.teams.chat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player chat channel preference + chat rate limiting state (hot path,
 * no database access). The persisted preference lives in
 * {@code rb_chat_settings}; this tracker mirrors it for online players and
 * is refreshed on join and on every {@code /chat} switch.
 */
public final class ChatTracker {

    public static final String CHANNEL_GLOBAL = "global";
    public static final String CHANNEL_TEAM = "team";
    public static final String CHANNEL_PROXIMITY = "proximity";

    private final Map<UUID, String> channels = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMessageAt = new ConcurrentHashMap<>();

    /** Stores the player's channel (validated by the caller). */
    public void setChannel(UUID player, String channel) {
        channels.put(player, channel);
    }

    /** @return the live channel, or the default when the player has none. */
    public String channel(UUID player, String fallback) {
        String channel = channels.get(player);
        return channel == null ? fallback : channel;
    }

    public void evict(UUID player) {
        channels.remove(player);
        lastMessageAt.remove(player);
    }

    /**
     * Rate gate for non-global channels: at most one message per
     * {@code intervalMs}; resets nothing, allows bursts only when the
     * interval is zero.
     *
     * @return true when the message may proceed
     */
    public boolean tryConsume(UUID player, long intervalMs) {
        if (intervalMs <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long previous = lastMessageAt.put(player, now);
        return previous == null || now - previous >= intervalMs;
    }
}
