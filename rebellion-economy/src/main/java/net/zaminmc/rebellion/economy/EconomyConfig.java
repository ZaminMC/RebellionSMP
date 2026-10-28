package net.zaminmc.rebellion.economy;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

/**
 * Validated snapshot of RebellionEconomy's operator contract (ADR-0021).
 * Immutable; a reload swaps the whole record atomically — the established
 * RpgConfig/AlchemyConfig pattern (ADR-0004).
 *
 * <h2>Families</h2>
 * <ul>
 *   <li><strong>currency</strong> — display identity only: name, symbol
 *       and the minor-unit scale (how many minor units make one major
 *       unit, e.g. 100). Balances are stored as exact long minor units;
 *       the scale never touches arithmetic, only formatting (controller
 *       phase).</li>
 *   <li><strong>bounds</strong> — {@code max-balance} minor units: the
 *       overflow answer for §5.9's "never use unchecked integer
 *       multiplication that can overflow". Every accumulate is checked
 *       against this bound before the write, and the config itself is
 *       validated to keep the bound small enough that one credit plus one
 *       balance can never overflow a long.</li>
 * </ul>
 *
 * <p>There is deliberately no faucet, shop, salary or transfer surface here
 * (§5.9: "do not create an unrequested sprawling shop/auction economy").
 * Balances move only through the provider's audited credit/debit, called
 * by modules (prison fees) and future admin commands.</p>
 */
public record EconomyConfig(@NotNull Currency currency, long maxBalanceMinor) {

    /** Idempotency-key grammar: caller-composed, stable across replays. */
    public static final Pattern KEY = Pattern.compile("[a-z0-9_.:\\-]{1,96}");

    /** Audit-reason grammar: dot-separated lowercase words. */
    public static final Pattern REASON = Pattern.compile("[a-z0-9_.:\\-]{1,64}");

    /**
     * Ceiling on the configurable maximum: keeps {@code 2 * maxBalance} and
     * any single accumulate of {@code maxBalance + amount} comfortably
     * inside long range, so the bound check is the only overflow guard
     * needed.
     */
    public static final long MAX_BALANCE_LIMIT = 1L << 62;

    public record Currency(@NotNull String name, @NotNull String symbol, int scale) {
    }

    public record LoadResult(EconomyConfig config, List<String> issues) {
        public boolean valid() {
            return config != null && issues.isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /**
     * Test/operator convenience: the shipped defaults — "Rebellion Marks"
     * ({@code RM}) at scale 100 (two fractional digits when displayed),
     * with a generous but overflow-safe maximum of 10^12 major units.
     */
    public static EconomyConfig defaults() {
        return new EconomyConfig(new Currency("Rebellion Marks", "RM", 100),
                1_000_000_000_000_00L);
    }

    /** @return the config parsed from a YAML document, collecting every issue. */
    public static LoadResult load(@NotNull String yaml) {
        YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(yaml);
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            return new LoadResult(null, List.of("not valid YAML: "
                    + e.getMessage()));
        }
        return loadResult(parsed);
    }

    /** @return the config read from an already-loaded document. */
    public static LoadResult loadResult(@NotNull YamlConfiguration root) {
        return from(root);
    }

    private static LoadResult from(ConfigurationSection root) {
        List<String> issues = new java.util.ArrayList<>();
        Currency currency = readCurrency(root.getConfigurationSection("currency"),
                issues);
        long maxBalance = readLong(root, "bounds.max-balance",
                defaults().maxBalanceMinor(), issues);
        if (maxBalance < 1 || maxBalance > MAX_BALANCE_LIMIT) {
            issues.add("bounds.max-balance must be 1.." + MAX_BALANCE_LIMIT
                    + " minor units (got " + maxBalance + ")");
            maxBalance = Math.max(1, Math.min(MAX_BALANCE_LIMIT, maxBalance));
        }
        if (currency == null) {
            return new LoadResult(null, issues);
        }
        return new LoadResult(new EconomyConfig(currency, maxBalance), issues);
    }

    private static Currency readCurrency(ConfigurationSection section,
            List<String> issues) {
        if (section == null) {
            // A missing section is the shipped-defaults case (a fresh install
            // or a minimal operator file) — silently default, no issue.
            return defaults().currency();
        }
        String name = section.getString("name", "Rebellion Marks").trim();
        String symbol = section.getString("symbol", "RM").trim();
        int scale = section.getInt("scale", 100);
        if (name.isEmpty() || name.length() > 48) {
            issues.add("currency.name must be 1..48 characters (got '" + name + "')");
        }
        if (symbol.isEmpty() || symbol.length() > 8) {
            issues.add("currency.symbol must be 1..8 characters (got '" + symbol + "')");
        }
        if (scale < 1 || scale > 1_000_000) {
            issues.add("currency.scale must be 1..1000000 (got " + scale + ")");
            scale = Math.max(1, Math.min(1_000_000, scale));
        }
        return new Currency(name, symbol, scale);
    }

    private static long readLong(ConfigurationSection root, String path,
            long fallback, List<String> issues) {
        Object raw = root.get(path);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        issues.add(path + ": expected an integer, got '" + raw + "'");
        return fallback;
    }
}
