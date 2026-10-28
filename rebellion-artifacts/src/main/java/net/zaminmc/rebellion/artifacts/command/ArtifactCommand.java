package net.zaminmc.rebellion.artifacts.command;

import java.util.List;
import java.util.Locale;
import net.zaminmc.rebellion.artifacts.Artifact;
import net.zaminmc.rebellion.artifacts.ArtifactKey;
import net.zaminmc.rebellion.artifacts.ArtifactNames;
import net.zaminmc.rebellion.artifacts.ArtifactOutcome;
import net.zaminmc.rebellion.artifacts.ArtifactService;
import net.zaminmc.rebellion.artifacts.ArtifactsConfig;
import net.zaminmc.rebellion.artifacts.RebellionEvent;
import net.zaminmc.rebellion.artifacts.item.ArtifactItems;
import net.zaminmc.rebellion.artifacts.purge.PurgeExecutor;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /artifact} — the player-facing artifact surface (master prompt
 * §5.5 command family). Subcommands: {@code status} (the three rows),
 * {@code return} (voluntary surrender of a held artifact, literal
 * confirm), {@code spend} (the overpowering action during an ACTIVE
 * occurrence, trigger holder only, literal confirm), {@code purge} (the
 * config-gated world-wide kill, quadruple-gated at runtime, ADR-0015 §4).
 *
 * <p>Authority never comes from client input: item identity is a PDC
 * stamp validated through the live mirror, and every mutation renders
 * through the typed outcome keys of the module's messages.yml (ADR-0012).
 * Confirm keywords are literals — no free text is ever executed.</p>
 */
public final class ArtifactCommand implements CommandExecutor, TabCompleter {

    static final String CONFIRM = "confirm";

    private static final List<String> SUBS =
            List.of("status", "return", "spend", "purge");

    private final org.bukkit.plugin.Plugin plugin;
    private final ArtifactService service;
    private final ArtifactsConfig config;
    private final ArtifactItems items;
    private final MessageCatalog messages;
    private final PurgeExecutor purge;

    public ArtifactCommand(@NotNull org.bukkit.plugin.Plugin plugin,
            @NotNull ArtifactService service, @NotNull ArtifactsConfig config,
            @NotNull ArtifactItems items, @NotNull MessageCatalog messages,
            @NotNull PurgeExecutor purge) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.items = items;
        this.messages = messages;
        this.purge = purge;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.msg("player-only"));
            return true;
        }
        if (!player.hasPermission("rebellion.artifacts.use")) {
            player.sendMessage(messages.msg("common.no-permission"));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(messages.msg("usage"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(player);
            case "return" -> voluntaryReturn(player, args);
            case "spend" -> spend(player, args);
            case "purge" -> purge(player, args);
            default -> player.sendMessage(messages.msg("usage"));
        }
        return true;
    }

    private void status(@NotNull Player player) {
        player.sendMessage(messages.msg("status.header"));
        for (Artifact artifact : service.all()) {
            player.sendMessage(messages.msg("status.line",
                    Placeholder.component("artifact", ArtifactNames.shortName(
                            messages, artifact.artifactKey())),
                    Placeholder.component("state", messages.raw(stateKey(artifact))),
                    Placeholder.unparsed("holder", holderName(artifact)),
                    Placeholder.unparsed("since", sinceStamp(artifact))));
        }
        service.activeEvent().ifPresent(event -> player.sendMessage(
                messages.msg("status.event",
                        Placeholder.unparsed("event", event.eventId().toString()),
                        Placeholder.unparsed("holder", event.triggerHolder().toString()))));
    }

    private void voluntaryReturn(@NotNull Player player, @NotNull String[] args) {
        Artifact held = heldArtifact(player);
        if (held == null) {
            player.sendMessage(messages.msg("return.none-held"));
            return;
        }
        if (args.length < 2 || !CONFIRM.equals(args[1].toLowerCase(Locale.ROOT))) {
            player.sendMessage(messages.msg("return.confirm",
                    Placeholder.component("artifact", ArtifactNames.shortName(
                            messages, held.artifactKey()))));
            return;
        }
        ItemStack presented = heldStack(player, held);
        if (presented == null) {
            player.sendMessage(messages.msg("return.none-held"));
            return;
        }
        var identity = items.read(presented);
        service.returnToOrigin(held.artifactKey(), player.getUniqueId(),
                        identity == null ? held.instance() : identity.instance())
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (result.outcome() == ArtifactOutcome.RETURN_SUBMITTED) {
                        removeStack(player, presented);
                        player.sendMessage(messages.msg("return.ok",
                                Placeholder.component("artifact", ArtifactNames.shortName(
                                        messages, held.artifactKey()))));
                    } else {
                        player.sendMessage(messages.msg("return.refused",
                                Placeholder.component("artifact", ArtifactNames.shortName(
                                        messages, held.artifactKey())),
                                Placeholder.unparsed("reason", String.valueOf(
                                        result.outcome()))));
                    }
                }));
    }

    private void spend(@NotNull Player player, @NotNull String[] args) {
        RebellionEvent event = service.activeEvent().orElse(null);
        if (event == null) {
            player.sendMessage(messages.msg("spend.no-event"));
            return;
        }
        if (!event.triggerHolder().equals(player.getUniqueId())) {
            player.sendMessage(messages.msg("spend.not-holder"));
            return;
        }
        if (args.length < 2 || !CONFIRM.equals(args[1].toLowerCase(Locale.ROOT))) {
            player.sendMessage(messages.msg("spend.confirm",
                    Placeholder.unparsed("event", event.eventId().toString())));
            return;
        }
        service.spendArtifacts(player.getUniqueId(),
                "command spend by " + player.getName())
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (result.outcome() == ArtifactOutcome.SPENT) {
                        removeAllLiveStacks(player);
                        player.sendMessage(messages.msg("spend.ok",
                                Placeholder.unparsed("event", event.eventId().toString())));
                    } else {
                        player.sendMessage(messages.msg("spend.refused",
                                Placeholder.unparsed("reason",
                                        String.valueOf(result.outcome()))));
                    }
                }));
    }

    private void purge(@NotNull Player player, @NotNull String[] args) {
        boolean confirmed = args.length >= 2
                && CONFIRM.equals(args[1].toLowerCase(Locale.ROOT));
        String refusal = purge.refusalFor(player, confirmed);
        if (refusal == null) {
            purge.execute(player);
            return;
        }
        if ("purge.confirm".equals(refusal)) {
            player.sendMessage(messages.msg("purge.confirm"));
        } else {
            player.sendMessage(messages.msg(refusal));
        }
    }

    // ------------------------------------------------------------------
    // Inventory helpers (PDC-validated, mirror-checked)
    // ------------------------------------------------------------------

    /** @return the one artifact this player currently holds, if any. */
    private @org.jetbrains.annotations.Nullable Artifact heldArtifact(
            @NotNull Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            var identity = items.read(stack);
            if (identity == null) {
                continue;
            }
            var artifact = service.byInstance(identity.instance()).orElse(null);
            if (artifact != null && artifact.state()
                    == net.zaminmc.rebellion.artifacts.ArtifactState.HELD
                    && player.getUniqueId().equals(artifact.holder())) {
                return artifact;
            }
        }
        return null;
    }

    /** @return the live-stamped stack of {@code artifact} in the inventory. */
    private @org.jetbrains.annotations.Nullable ItemStack heldStack(
            @NotNull Player player, @NotNull Artifact artifact) {
        for (ItemStack stack : player.getInventory().getContents()) {
            var identity = items.read(stack);
            if (identity != null && identity.instance().equals(artifact.instance())) {
                return stack;
            }
        }
        return null;
    }

    private void removeStack(@NotNull Player player, @NotNull ItemStack stack) {
        player.getInventory().remove(stack);
    }

    /** Spent: every live-stamped stack dies with the superseded stamps. */
    private void removeAllLiveStacks(@NotNull Player player) {
        var contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            var identity = items.read(stack);
            if (identity == null) {
                continue;
            }
            var artifact = service.byInstance(identity.instance()).orElse(null);
            if (artifact != null
                    && (artifact.state()
                        == net.zaminmc.rebellion.artifacts.ArtifactState.SPENT
                    || artifact.state()
                        == net.zaminmc.rebellion.artifacts.ArtifactState.RETURNING)) {
                contents[i] = null;
            }
        }
        player.getInventory().setContents(contents);
    }

    private static @NotNull String stateKey(@NotNull Artifact artifact) {
        return "state." + artifact.state().name().toLowerCase(Locale.ROOT);
    }

    private static @NotNull String holderName(@NotNull Artifact artifact) {
        return artifact.holder() == null ? "-" : artifact.holder().toString();
    }

    private static @NotNull String sinceStamp(@NotNull Artifact artifact) {
        if (artifact.stateSinceUtc() <= 0) {
            return "-";
        }
        return java.time.Instant.ofEpochMilli(artifact.stateSinceUtc())
                .toString();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
            @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 1) {
            return SUBS.stream()
                    .filter(sub -> sub.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("return")
                || args[0].equalsIgnoreCase("spend")
                || args[0].equalsIgnoreCase("purge"))) {
            return List.of(CONFIRM);
        }
        return List.of();
    }

    /** Artifact key parse helper shared with the admin command. */
    static @org.jetbrains.annotations.Nullable ArtifactKey parseKey(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return ArtifactKey.parse(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
