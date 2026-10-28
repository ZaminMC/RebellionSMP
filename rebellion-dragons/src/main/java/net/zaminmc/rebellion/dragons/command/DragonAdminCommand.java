package net.zaminmc.rebellion.dragons.command;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.zaminmc.rebellion.api.messages.MessageCatalog;
import net.zaminmc.rebellion.dragons.Lineage;
import net.zaminmc.rebellion.dragons.LineageService;
import net.zaminmc.rebellion.dragons.control.PetController;
import net.zaminmc.rebellion.dragons.control.PetController.Delivery;
import net.zaminmc.rebellion.dragons.db.LineageStore;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /dragonadmin} — audited lineage administration: {@code list},
 * {@code info <slot>}, {@code route <slot> <player>} (recovery/eggs only,
 * online target for delivery), {@code audit <slot>} (cold trail) and
 * {@code retire <slot> confirm [reason]} — retirement is terminal and
 * requires the literal confirm keyword in the same invocation; the command
 * layer owns that confirmation (ADR-0009 §6).
 *
 * <p>Nothing here mints: routing and claiming are pure re-issues of
 * existing lineages with fresh egg instances, and retirement is the only
 * terminal action. Every mutation appends its audit row inside the same
 * transaction as the state change.</p>
 */
public final class DragonAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS =
            List.of("list", "info", "route", "audit", "retire");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final LineageService lineages;
    private final PetController controller;
    private final MessageCatalog messages;

    public DragonAdminCommand(@NotNull LineageService lineages,
            @NotNull PetController controller, @NotNull MessageCatalog messages) {
        this.lineages = lineages;
        this.controller = controller;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, String[] args) {
        if (!sender.hasPermission("rebellion.dragons.admin")) {
            sender.sendMessage(messages.msg("common.no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(messages.msg("admin.usage"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender);
            case "info" -> withSlot(sender, args, 2, this::info);
            case "audit" -> withSlot(sender, args, 2, this::audit);
            case "route" -> route(sender, args);
            case "retire" -> retire(sender, args);
            default -> sender.sendMessage(messages.msg("admin.usage"));
        }
        return true;
    }

    // ------------------------------------------------------------------
    // list / info / audit
    // ------------------------------------------------------------------

    private void list(CommandSender sender) {
        List<Lineage> all = lineages.all();
        if (all.isEmpty()) {
            sender.sendMessage(messages.msg("admin.list-empty"));
            return;
        }
        sender.sendMessage(messages.msg("admin.list-header",
                Placeholder.unparsed("count", String.valueOf(all.size())),
                Placeholder.unparsed("cap", String.valueOf(lineages.cap()))));
        for (Lineage lineage : all) {
            sender.sendMessage(messages.msg("admin.list-line",
                    Placeholder.unparsed("slot", String.valueOf(lineage.slot())),
                    stateResolver(lineage),
                    Placeholder.unparsed("owner", ownerName(lineage)),
                    Placeholder.unparsed("origin", lineage.originWorld()),
                    Placeholder.unparsed("x", String.valueOf(lineage.gatewayX())),
                    Placeholder.unparsed("y", String.valueOf(lineage.gatewayY())),
                    Placeholder.unparsed("z", String.valueOf(lineage.gatewayZ()))));
        }
    }

    private void info(CommandSender sender, Lineage lineage) {
        sender.sendMessage(messages.msg("dragon.status.header",
                Placeholder.unparsed("slot", String.valueOf(lineage.slot())),
                stateResolver(lineage)));
        lineage.locationOpt().ifPresent(loc -> sender.sendMessage(messages.msg(
                "dragon.status.detail", stateResolver(lineage),
                Placeholder.unparsed("health", lineage.health() == null ? "unknown"
                        : String.format(Locale.ROOT, "%.1f", lineage.health())),
                Placeholder.unparsed("where", loc.world() + " " + (int) loc.x() + ", "
                        + (int) loc.y() + ", " + (int) loc.z()))));
        audit(sender, lineage);
    }

    private void audit(CommandSender sender, Lineage lineage) {
        lineages.auditTrail(lineage.lineageId()).thenAccept(rows ->
                Bukkit.getScheduler().runTask(controller.plugin(), () -> {
                    if (rows.isEmpty()) {
                        sender.sendMessage(messages.msg("admin.audit-none"));
                        return;
                    }
                    sender.sendMessage(messages.msg("admin.audit-header",
                            Placeholder.unparsed("slot", String.valueOf(lineage.slot())),
                            Placeholder.unparsed("count", String.valueOf(rows.size()))));
                    for (LineageStore.AuditRow row : rows) {
                        sender.sendMessage(messages.msg("admin.audit-line",
                                Placeholder.unparsed("action", row.action()),
                                Placeholder.unparsed("actor", actor(row.actor())),
                                Placeholder.unparsed("time", TIME.format(
                                        Instant.ofEpochMilli(row.atUtc()))),
                                Placeholder.unparsed("detail", row.detail() == null ? ""
                                        : " - " + row.detail())));
                    }
                }));
    }

    private String actor(UUID uuid) {
        if (uuid == null) {
            return "console";
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    // ------------------------------------------------------------------
    // route
    // ------------------------------------------------------------------

    private void route(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(messages.msg("admin.route.usage"));
            return;
        }
        Optional<Lineage> found = bySlot(args[1]);
        if (found.isEmpty()) {
            sender.sendMessage(messages.msg("admin.not-found"));
            return;
        }
        Lineage lineage = found.get();
        if (!lineage.state().isEgg()) {
            sender.sendMessage(messages.msg("admin.route.usage"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(messages.msg("admin.route.not-online"));
            return;
        }
        UUID admin = sender instanceof Player p ? p.getUniqueId() : null;
        lineages.adminRouteEgg(lineage.lineageId(), admin, target.getUniqueId())
                .thenAccept(result -> Bukkit.getScheduler().runTask(controller.plugin(),
                        () -> {
                            if (result.success() && result.lineage() != null) {
                                Delivery delivery = controller.deliverEgg(
                                        target.getUniqueId(), result.lineage());
                                sender.sendMessage(messages.msg("admin.route.ok",
                                        Placeholder.unparsed("slot",
                                                String.valueOf(lineage.slot())),
                                        Placeholder.unparsed("player", target.getName())));
                                if (delivery == Delivery.UNDELIVERABLE) {
                                    lineages.markEggUndeliverable(
                                            result.lineage().lineageId(),
                                            "route target offline at delivery");
                                }
                            } else {
                                sender.sendMessage(messages.msg("admin.not-found"));
                            }
                        }));
    }

    // ------------------------------------------------------------------
    // retire (terminal; confirm keyword required)
    // ------------------------------------------------------------------

    private void retire(CommandSender sender, String[] args) {
        if (args.length < 3 || !"confirm".equalsIgnoreCase(args[2])) {
            // First call explains the stakes; the literal confirm keyword is
            // the operator's confirmation (command-layer contract, ADR-0009).
            Optional<Lineage> found = args.length >= 2 ? bySlot(args[1]) : Optional.empty();
            if (found.isEmpty()) {
                sender.sendMessage(messages.msg("admin.retire.usage"));
                return;
            }
            sender.sendMessage(messages.msg("admin.retire.confirm",
                    Placeholder.unparsed("slot",
                            String.valueOf(found.get().slot()))));
            return;
        }
        Optional<Lineage> found = bySlot(args[1]);
        if (found.isEmpty()) {
            sender.sendMessage(messages.msg("admin.not-found"));
            return;
        }
        Lineage lineage = found.get();
        UUID admin = sender instanceof Player p ? p.getUniqueId() : null;
        String reason = String.join(" ",
                java.util.Arrays.asList(args).subList(3, args.length));
        lineages.retire(lineage.lineageId(), admin, reason.isEmpty() ? null : reason)
                .thenAccept(result -> Bukkit.getScheduler().runTask(controller.plugin(),
                        () -> {
                            if (result.outcome() == net.zaminmc.rebellion.dragons.LineageOutcome.RETIRED) {
                                sender.sendMessage(messages.msg("admin.retire.ok",
                                        Placeholder.unparsed("slot",
                                                String.valueOf(lineage.slot()))));
                            } else {
                                sender.sendMessage(messages.msg("admin.not-found"));
                            }
                        }));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private interface SlotAction {
        void run(CommandSender sender, Lineage lineage);
    }

    private void withSlot(CommandSender sender, String[] args, int min, SlotAction action) {
        if (args.length < min) {
            sender.sendMessage(messages.msg("admin.usage"));
            return;
        }
        Optional<Lineage> found = bySlot(args[1]);
        if (found.isEmpty()) {
            sender.sendMessage(messages.msg("admin.not-found"));
            return;
        }
        action.run(sender, found.get());
    }

    private Optional<Lineage> bySlot(String raw) {
        int slot;
        try {
            slot = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return lineages.all().stream()
                .filter(l -> l.slot() == slot)
                .findFirst();
    }

    private String ownerName(Lineage lineage) {
        return lineage.ownerOpt().map(uuid -> {
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            return name == null ? uuid.toString().substring(0, 8) : name;
        }).orElse("unknown");
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
    // tab completion
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2) {
            String prefix = args[1];
            return lineages.all().stream()
                    .map(l -> String.valueOf(l.slot()))
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }
        if (args.length == 3 && "route".equalsIgnoreCase(args[0])) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .limit(20)
                    .toList();
        }
        return List.of();
    }
}
