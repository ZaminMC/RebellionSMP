package net.zaminmc.rebellion.items.audit;

import net.zaminmc.rebellion.items.identity.ItemIdentity;

/**
 * One visited stack in an audit pass. {@code identity} is null for vanilla
 * stacks without RebellionSMP identity; those are counted but not reported
 * line by line.
 */
public record AuditEntry(String container, int slot, String material, int amount,
        ItemIdentity identity) {

    public AuditEntry {
        if (container == null || container.isBlank()) {
            throw new IllegalArgumentException("container label required");
        }
        if (amount < 1) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
