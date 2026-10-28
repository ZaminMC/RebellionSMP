package net.zaminmc.rebellion.artifacts.origin;

import net.zaminmc.rebellion.artifacts.ArtifactNames;
import net.zaminmc.rebellion.artifacts.Artifact;
import net.zaminmc.rebellion.artifacts.ArtifactKey;
import net.zaminmc.rebellion.artifacts.ArtifactOutcome;
import net.zaminmc.rebellion.artifacts.ArtifactResult;
import net.zaminmc.rebellion.artifacts.ArtifactService;
import net.zaminmc.rebellion.artifacts.ArtifactsConfig;
import net.zaminmc.rebellion.artifacts.event.RebellionObserver;
import net.zaminmc.rebellion.artifacts.item.ArtifactItems;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Origin presentation protection + capture (ADR-0015 §1). The pair is
 * invulnerable to everything except our own teardown (cause PLUGIN) and
 * chunk unloads: damage and external removals are cancelled, so the
 * discoverable origin cannot be permanently deleted or griefed away
 * (master prompt §5.5 "protected" — no territory claim, just
 * undeletable). Right-clicking the Interaction attempts the capture: the
 * row must be ORIGIN and the built item carries the row's live instance,
 * so the CAS is the only authority — raced or cloned clicks lose
 * structurally. An inventory-full discoverer is refused; the presentation
 * stays for the next conqueror.
 */
public final class OriginListener implements Listener {

    private final org.bukkit.plugin.Plugin plugin;
    private final ArtifactService service;
    private final ArtifactsConfig config;
    private final ArtifactItems items;
    private final MessageCatalog messages;
    private final OriginPresentation presentation;
    private final RebellionObserver observer;

    public OriginListener(@NotNull org.bukkit.plugin.Plugin plugin,
            @NotNull ArtifactService service, @NotNull ArtifactsConfig config,
            @NotNull ArtifactItems items, @NotNull MessageCatalog messages,
            @NotNull OriginPresentation presentation,
            @NotNull RebellionObserver observer) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.items = items;
        this.messages = messages;
        this.presentation = presentation;
        this.observer = observer;
    }

    /** Presentation entities are invulnerable. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (presentation.isPresentation(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** Right-click on the Interaction = capture attempt. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return; // avoid double-fire for the off hand
        }
        ArtifactKey key = presentation.keyOf(event.getRightClicked());
        if (key == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Artifact artifact = service.byKey(key).orElse(null);
        if (artifact == null) {
            return;
        }
        if (artifact.state() != net.zaminmc.rebellion.artifacts.ArtifactState.ORIGIN) {
            player.sendMessage(messages.msg("origin.not-origin",
                    Placeholder.component("artifact", ArtifactNames.shortName(
                            messages, key))));
            return;
        }
        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(messages.msg("origin.inventory-full",
                    Placeholder.component("artifact", ArtifactNames.shortName(
                            messages, key))));
            return;
        }
        // Build from the row read this tick; the CAS is the sole authority.
        ItemStack stack = items.build(artifact, config, messages);
        service.capture(key, artifact.instance(), player.getUniqueId())
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (result.outcome() == ArtifactOutcome.CAPTURED) {
                        presentation.removePresentation(key);
                        player.getInventory().addItem(stack);
                        player.sendMessage(messages.msg("origin.captured",
                                Placeholder.component("artifact", ArtifactNames.shortName(
                                        messages, key))));
                        observer.checkPossession(player);
                    } else {
                        player.sendMessage(messages.msg("origin.capture-refused",
                                Placeholder.component("artifact", ArtifactNames.shortName(
                                        messages, key)),
                                Placeholder.unparsed("reason", reason(result))));
                    }
                }));
    }

    private static String reason(@NotNull ArtifactResult result) {
        return switch (result.outcome()) {
            case INSTANCE_STALE -> "stale or cloned copy";
            case INVALID_STATE -> "no longer at its origin";
            case FEATURE_DISABLED -> "disabled";
            default -> result.detail() == null ? "refused" : result.detail();
        };
    }
}
