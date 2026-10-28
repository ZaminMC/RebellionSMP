package net.zaminmc.rebellion.dragons.command;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.dragons.DragonsConfig;
import net.zaminmc.rebellion.dragons.Lineage;
import net.zaminmc.rebellion.dragons.LineageService;
import net.zaminmc.rebellion.dragons.LineageState;
import net.zaminmc.rebellion.dragons.control.PetController;
import net.zaminmc.rebellion.dragons.control.PetController.AttackOutcome;
import net.zaminmc.rebellion.dragons.control.PetController.AttackResult;
import net.zaminmc.rebellion.dragons.control.PetController.CallOutcome;
import net.zaminmc.rebellion.dragons.control.PetController.Delivery;
import net.zaminmc.rebellion.dragons.control.PetController.MountOutcome;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /dragon} — the player-facing pet lifecycle (master prompt §5.6
 * command family). Subcommands: {@code hatch} (held egg, holder-only),
 * {@code status}, {@code call} (cooldown + range), {@code mount}
 * (config-gated ride; right-clicking the pet is the horse-like equivalent),
 * {@code dismount}/{@code dismiss} (dismount + settle), {@code attack}
 * (crosshair target, budgeted fireball), {@code claim} (recovery-egg
 * mailbox).
 *
 * <p>Authority is never taken from client input: egg identity comes from
 * PDC stamps validated against the live mirror, target resolution from the
 * server raytrace, and every mutation result renders through the typed
 * outcome keys of the module's messages.yml catalog (ADR-0012). All entity
 * operations are delegated to the controller; this class only routes and
 * renders.</p>
 */
public final class DragonCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS =
            List.of("hatch", "status", "call", "mount", "dismount", "dismiss", "attack",
                    "claim");

    private final LineageService lineages;
    private final PetController controller;
    private final DragonsConfig config;
    private final MessageCatalog messages;

    public DragonCommand(@NotNull LineageService lineages,
            @NotNull PetController controller, @NotNull DragonsConfig config,
            @NotNull MessageCatalog messages) {
        this.lineages = lineages;
        this.controller = controller;
        this.config = config;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.msg("player-only"));
            return true;
        }
        if (!player.hasPermission("rebellion.dragons.use")) {
            player.sendMessage(messages.msg("common.no-permission"));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(messages.msg("usage",
                    Placeholder.unparsed("cap", String.valueOf(lineages.cap()))));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "hatch" -> hatch(player);
            case "status" -> status(player);
            case "call" -> call(player);
            case "mount" -> mount(player);
            case "dismount" -> dismount(player);
            case "dismiss" -> dismiss(player);
            case "attack" -> attack(player);
            case "claim" -> claim(player);
            default -> player.sendMessage(messages.msg("usage",
                    Placeholder.unparsed("cap", String.valueOf(lineages.cap()))));
        }
        return true;
    }

    // ------------------------------------------------------------------
    // hatch
    // ------------------------------------------------------------------

    private void hatch(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        controller.hatch(player, held);
    }

    // ------------------------------------------------------------------
    // status
    // ------------------------------------------------------------------

    private void status(Player player) {
        UUID self = player.getUniqueId();
        Optional<Lineage> owned = lineages.all().stream()
                .filter(l -> self.equals(l.owner()))
                .findFirst();
        if (owned.isEmpty()) {
            player.sendMessage(messages.msg("dragon.status.none"));
            return;
        }
        Lineage lineage = owned.get();
        TagResolver slot = Placeholder.unparsed("slot", String.valueOf(lineage.slot()));
        player.sendMessage(messages.msg("dragon.status.header", slot,
                stateResolver(lineage)));
        String where = lineage.locationOpt()
                .map(loc -> loc.world() + " " + (int) loc.x() + ", " + (int) loc.y() + ", "
                        + (int) loc.z())
                .orElse(plain("dragon.status.location-unknown"));
        String health = lineage.health() == null ? "unknown"
                : String.format(Locale.ROOT, "%.1f", lineage.health());
        player.sendMessage(messages.msg("dragon.status.detail",
                stateResolver(lineage),
                Placeholder.unparsed("health", health),
                Placeholder.unparsed("where", where)));
        long pearlRemaining = controller.pearlCooldownRemainingMs(lineage,
                System.currentTimeMillis());
        TagResolver pearl = Placeholder.unparsed("pearl", pearlRemaining <= 0
                ? plain("dragon.status.pearl-ready")
                : (pearlRemaining / 1000L) + "s cooldown");
        player.sendMessage(messages.msg("dragon.status.pearl", pearl,
                controller.budgetResolvers(lineage)));
        player.sendMessage(messages.msg("dragon.status.gateway", slot,
                Placeholder.unparsed("origin", lineage.originWorld()),
                Placeholder.unparsed("x", String.valueOf(lineage.gatewayX())),
                Placeholder.unparsed("y", String.valueOf(lineage.gatewayY())),
                Placeholder.unparsed("z", String.valueOf(lineage.gatewayZ()))));
    }

    /** Plain-text rendering of a catalog key (inline status labels). */
    private String plain(String key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(messages.raw(key));
    }

    private TagResolver stateResolver(Lineage lineage) {
        String key = switch (lineage.state()) {
            case EGG_HELD -> "dragon.status.state.egg-held";
            case EGG_RECOVERY -> "dragon.status.state.egg-recovery";
            case HATCHED -> "dragon.status.state.hatched";
            case RETIRED -> "dragon.status.state.retired";
        };
        return Placeholder.component("state", messages.raw(key));
    }

    // ------------------------------------------------------------------
    // call / mount / dismiss
    // ------------------------------------------------------------------

    private void call(Player player) {
        controller.call(player, outcome -> {
            switch (outcome) {
                case OK -> player.sendMessage(messages.msg("dragon.call.ok",
                        Placeholder.unparsed("offset",
                                String.valueOf(config.callHoverOffsetBlocks()))));
                case COOLDOWN -> player.sendMessage(messages.msg("dragon.call.cooldown",
                        Placeholder.unparsed("seconds", secondsOf(player))));
                case TOO_FAR -> player.sendMessage(messages.msg("dragon.call.too-far",
                        Placeholder.unparsed("distance", distanceOf(player)),
                        Placeholder.unparsed("range",
                                String.valueOf(config.callMaxRangeBlocks()))));
                case NOT_HATCHED -> player.sendMessage(messages.msg("dragon.call.not-hatched"));
                case NOT_RESOLVED -> player.sendMessage(
                        messages.msg("dragon.call.not-found"));
                default -> player.sendMessage(messages.msg("common.unavailable"));
            }
        });
    }

    private String secondsOf(Player player) {
        long remaining = controller.callCooldownRemainingMs(player.getUniqueId());
        return String.valueOf(remaining / 1000L);
    }

    private String distanceOf(Player player) {
        return controller.hatchedOf(player.getUniqueId())
                .flatMap(Lineage::locationOpt)
                .map(loc -> String.valueOf((int) Math.round(distanceBetween(player, loc))))
                .orElse(">");
    }

    private double distanceBetween(Player player, Lineage.PersistedLocation loc) {
        var world = player.getWorld();
        if (!world.getName().equals(loc.world())) {
            return config.callMaxRangeBlocks() + 1.0; // cross-world is not "too far"
        }
        return player.getLocation().distance(new org.bukkit.Location(world,
                loc.x(), loc.y(), loc.z()));
    }

    private void mount(Player player) {
        switch (controller.mount(player)) {
            case OK -> player.sendMessage(messages.msg("dragon.mount.ok"));
            case DISABLED -> player.sendMessage(messages.msg("dragon.mount.disabled"));
            case TOO_FAR -> player.sendMessage(messages.msg("dragon.mount.too-far"));
            case NOT_HATCHED -> player.sendMessage(messages.msg("dragon.mount.not-hatched"));
            default -> player.sendMessage(messages.msg("common.unavailable"));
        }
    }

    /**
     * Explicit dismount for the rider (sneak already dismounts vanilla-
     * style; this is the command equivalent). Dismounting ejects and
     * settles the pet so it never flies on without its rider.
     */
    private void dismount(Player player) {
        controller.dismiss(player);
        player.sendMessage(messages.msg("dragon.dismount.ok"));
    }

    private void dismiss(Player player) {
        controller.dismiss(player);
        player.sendMessage(messages.msg("dragon.dismiss.ok"));
    }

    // ------------------------------------------------------------------
    // attack
    // ------------------------------------------------------------------

    private void attack(Player player) {
        AttackResult result = controller.attack(player);
        if (result.outcome() == AttackOutcome.OK) {
            var target = player.getTargetEntity((int) config.attackRangeBlocks());
            player.sendMessage(messages.msg("dragon.attack.ok",
                    Placeholder.unparsed("target", target == null ? "target"
                            : target.getName() == null ? "it" : target.getName())));
            return;
        }
        switch (result.outcome()) {
            case NO_TARGET -> player.sendMessage(messages.msg("dragon.attack.no-target",
                    Placeholder.unparsed("range",
                            String.valueOf((int) config.attackRangeBlocks()))));
            case BUDGET -> player.sendMessage(messages.msg("dragon.attack.budget",
                    Placeholder.unparsed("max",
                            String.valueOf(config.fireballMaxPerWindow())),
                    Placeholder.unparsed("window",
                            String.valueOf(config.fireballWindowSeconds())),
                    Placeholder.unparsed("retry",
                            String.valueOf((result.retryAfterMs() + 999) / 1000L))));
            case INTERVAL -> player.sendMessage(messages.msg("dragon.attack.interval",
                    Placeholder.unparsed("retry",
                            String.valueOf((result.retryAfterMs() + 999) / 1000L))));
            case NOT_HATCHED -> player.sendMessage(messages.msg("dragon.attack.not-hatched"));
            case FRIENDLY -> player.sendMessage(messages.msg("dragon.attack.friendly"));
            default -> player.sendMessage(messages.msg("common.unavailable"));
        }
    }

    // ------------------------------------------------------------------
    // claim (recovery mailbox)
    // ------------------------------------------------------------------

    private void claim(Player player) {
        Optional<Lineage> parked = lineages.all().stream()
                .filter(l -> l.state() == LineageState.EGG_RECOVERY)
                .filter(l -> player.getUniqueId().equals(l.owner()))
                .findFirst();
        if (parked.isEmpty()) {
            player.sendMessage(messages.msg("dragon.claim.none"));
            return;
        }
        Lineage lineage = parked.get();
        lineages.claimRecoveredEgg(lineage.lineageId(), player.getUniqueId())
                .thenAccept(result -> {
                    if (result.success() && result.lineage() != null) {
                        org.bukkit.Bukkit.getScheduler().runTask(
                                controllerPlugin(), () -> {
                                    Delivery delivery = controller.deliverEgg(
                                            player.getUniqueId(), result.lineage());
                                    renderClaim(player, result.lineage(), delivery);
                                });
                    } else {
                        org.bukkit.Bukkit.getScheduler().runTask(controllerPlugin(),
                                () -> player.sendMessage(
                                        messages.msg("dragon.claim.not-yours")));
                    }
                });
    }

    private void renderClaim(Player player, Lineage lineage, Delivery delivery) {
        switch (delivery) {
            case DELIVERED -> player.sendMessage(messages.msg("dragon.claim.ok",
                    Placeholder.unparsed("slot", String.valueOf(lineage.slot()))));
            case DROPPED -> {
                player.sendMessage(messages.msg("dragon.claim.ok",
                        Placeholder.unparsed("slot", String.valueOf(lineage.slot()))));
                player.sendMessage(messages.msg("egg.dropped"));
            }
            default -> player.sendMessage(messages.msg("egg.undeliverable"));
        }
    }

    private org.bukkit.plugin.Plugin controllerPlugin() {
        return controller.plugin();
    }

    // ------------------------------------------------------------------
    // tab completion
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
