package net.zaminmc.rebellion.wards.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.zaminmc.rebellion.wards.Ward;
import net.zaminmc.rebellion.wards.WardService;
import net.zaminmc.rebellion.wards.WardType;
import net.zaminmc.rebellion.wards.WardsConfig;
import net.zaminmc.rebellion.wards.WardsConfig.AlertChannel;
import net.zaminmc.rebellion.wards.detect.DetectionState;
import net.zaminmc.rebellion.wards.index.WardSpatialIndex;
import org.junit.jupiter.api.Test;

/**
 * {@link WardsMapMarkers} — the privacy gate (master prompt §14): ward
 * positions publish ONLY when {@code map-markers.publish-wards} is
 * explicitly true, and even then only active, real (non-decoy) wards.
 *
 * <p>Uses a real {@link WardService} over a real in-memory index; the read
 * paths under test ({@code all}) never touch persistence, mirroring how the
 * provider behaves between refreshes.</p>
 */
class WardsMapMarkersTest {

    private WardsConfig config(boolean publish) {
        return new WardsConfig("LODESTONE", 6, 16.0, 256.0, 3, 12, 2000,
                Set.of("leader", "officer"), true, 20, 0, true,
                "AMETHYST_SHARD", AlertChannel.TEAM, 300, true,
                Map.of(), "blocks", publish);
    }

    /** Service with a null database: marker reads never reach persistence. */
    private WardService serviceWith(Ward... wards) {
        WardSpatialIndex index = WardSpatialIndex.withDefaultCellSize();
        for (Ward ward : wards) {
            index.insert(ward);
        }
        return new WardService(null, config(false), index, new DetectionState(3_600_000L),
                null, System::currentTimeMillis);
    }

    private Ward ward(boolean active, WardType type) {
        return new Ward(UUID.randomUUID(), "north", UUID.randomUUID(), Optional.empty(),
                "world", 100.5, 64.5, 200.5, 64, type, active, 0L);
    }

    @Test
    void markersStayEmptyByDefaultAndForUnknownCategories() {
        WardService service = serviceWith(ward(true, WardType.WARD));
        WardsMapMarkers provider = new WardsMapMarkers(service, config(false));
        assertEquals(Set.of("wards"), provider.categories());
        assertTrue(provider.markers("wards").isEmpty(), "publish-wards defaults to false");
        assertTrue(provider.markers("rituals").isEmpty());
        assertTrue(provider.markers("").isEmpty());
    }

    @Test
    void enabledGatePublishesActiveRealWardsOnly() {
        Ward active = ward(true, WardType.WARD);
        Ward decoy = ward(true, WardType.DECOY);
        Ward inactive = ward(false, WardType.WARD);
        WardService service = serviceWith(active, decoy, inactive);

        WardsMapMarkers provider = new WardsMapMarkers(service, config(true));
        List<net.zaminmc.rebellion.api.map.MapMarker> markers = provider.markers("wards");
        assertEquals(1, markers.size());
        var marker = markers.get(0);
        assertEquals("wards", marker.category());
        assertEquals("world", marker.world());
        assertEquals(100.5, marker.x());
        assertEquals(64.5, marker.y());
        assertEquals(200.5, marker.z());
        assertTrue(marker.label().contains("north"));
        assertEquals("ward", marker.iconHint());
    }

    @Test
    void gateIsPerProviderInstanceNotGlobalState() {
        // Two providers over the same data with different gates: only the
        // enabled one publishes (no hidden global toggle).
        WardService service = serviceWith(ward(true, WardType.WARD));
        assertTrue(new WardsMapMarkers(service, config(false)).markers("wards").isEmpty());
        assertEquals(1, new WardsMapMarkers(service, config(true)).markers("wards").size());
        assertTrue(new WardsMapMarkers(service, config(false)).markers("wards").isEmpty(),
                "the disabled provider is still disabled after another enabled one");
    }
}
