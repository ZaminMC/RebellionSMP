package net.zaminmc.rebellion.wards.map;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.zaminmc.rebellion.api.map.MapMarker;
import net.zaminmc.rebellion.api.map.MapMarkerProvider;
import net.zaminmc.rebellion.wards.Ward;
import net.zaminmc.rebellion.wards.WardService;
import net.zaminmc.rebellion.wards.WardsConfig;
import org.jetbrains.annotations.NotNull;

/**
 * Ward marker publication, privacy-gated twice (master prompt §14):
 * the {@code map-markers.publish-wards} admin setting must be explicitly
 * true — default false — and even then only ACTIVE, REAL (non-decoy) wards
 * publish. A future web-map adapter re-checks the category before rendering
 * anything publicly.
 *
 * <p>Labels carry the ward name and a "private" hint; positions are the
 * anchor coordinates. This is the only surface where ward positions leave
 * the module, and it stays off unless an operator flips the gate with
 * intent.</p>
 */
public final class WardsMapMarkers implements MapMarkerProvider {

    static final String CATEGORY = "wards";

    private final WardService service;
    private final WardsConfig config;

    public WardsMapMarkers(WardService service, WardsConfig config) {
        this.service = service;
        this.config = config;
    }

    @Override
    public @NotNull Set<String> categories() {
        return Set.of(CATEGORY);
    }

    @Override
    public @NotNull List<MapMarker> markers(@NotNull String category) {
        if (!CATEGORY.equals(category) || !config.publishWardMarkers()) {
            return List.of(); // privacy-by-default; unknown category is empty
        }
        return service.all().stream()
                .filter(Ward::active)
                .filter(ward -> ward.type() == net.zaminmc.rebellion.wards.WardType.WARD)
                .map(ward -> new MapMarker(CATEGORY, ward.world(),
                        ward.x(), ward.y(), ward.z(),
                        "Watch ward: " + sanitize(ward.name()),
                        "ward"))
                .toList();
    }

    /** Ward names are charset-validated at creation; strip brackets anyway. */
    private static String sanitize(String value) {
        return value == null ? "" : value.replace("<", "").replace(">", "");
    }

    /** Unused now, kept for the marker id contract when the adapter lands. */
    @SuppressWarnings("unused")
    private static UUID markerId(Ward ward) {
        return ward.id();
    }
}
