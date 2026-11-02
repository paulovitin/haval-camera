package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

public final class VideoSettingsTest {
    @Test public void defaultsAreSafeAndComplete() {
        VideoSettings settings = new VideoSettings(new VideoSettings.MemoryStore());
        VideoSettings.Snapshot value = settings.snapshot();
        assertTrue(value.automaticRecording);
        assertFalse(value.recordParkedLive);
        assertTrue(value.remoteLive);
        assertTrue(value.remoteDownloads);
        assertEquals(VideoSettings.Quality.STANDARD, value.quality);
        assertEquals(5L << 30, value.reserveBytes);
        assertTrue(value.automaticDeletion);
        assertTrue(value.overlayMaster && value.overlayDateTime && value.overlaySpeed
                && value.overlayAcceleration && value.overlayDriveState
                && value.overlayDecelerationRegen);
        assertEquals(VideoSettings.OverlayPosition.BOTTOM_LEFT, value.overlayPosition);
    }

    @Test public void validatedPatchPersistsAtomically() {
        VideoSettings.MemoryStore store = new VideoSettings.MemoryStore();
        VideoSettings settings = new VideoSettings(store);
        VideoSettings.Snapshot changed = settings.applyPatch(Map.of(
                "quality", "economy", "reserveGb", 20,
                "overlaySpeed", false, "overlayPosition", "top_right"));
        VideoSettings.Snapshot restarted = new VideoSettings(store).snapshot();
        assertEquals(VideoSettings.Quality.ECONOMY, changed.quality);
        assertEquals(20L << 30, restarted.reserveBytes);
        assertFalse(restarted.overlaySpeed);
        assertEquals(VideoSettings.OverlayPosition.TOP_RIGHT, restarted.overlayPosition);
    }

    @Test public void unknownKeysAndUnsafeValuesFailWithoutMutation() {
        VideoSettings settings = new VideoSettings(new VideoSettings.MemoryStore());
        VideoSettings.Snapshot before = settings.snapshot();
        assertThrows(IllegalArgumentException.class,
                () -> settings.applyPatch(Map.of("quality", "custom")));
        assertThrows(IllegalArgumentException.class,
                () -> settings.applyPatch(Map.of("reserveGb", 1)));
        assertThrows(IllegalArgumentException.class,
                () -> settings.applyPatch(Map.of("unknown", true)));
        assertEquals(before, settings.snapshot());
    }

    @Test public void everyFeatureToggleAndPresetIsPersisted() {
        VideoSettings settings = new VideoSettings(new VideoSettings.MemoryStore());
        VideoSettings.Snapshot value = settings.applyPatch(Map.ofEntries(
                Map.entry("automaticRecording", false), Map.entry("recordParkedLive", true),
                Map.entry("remoteLive", false), Map.entry("remoteDownloads", false),
                Map.entry("automaticDeletion", false), Map.entry("overlayMaster", false),
                Map.entry("overlayDateTime", false), Map.entry("overlaySpeed", false),
                Map.entry("overlayAcceleration", false), Map.entry("overlayDriveState", false),
                Map.entry("overlayDecelerationRegen", false), Map.entry("quality", "high"),
                Map.entry("reserveGb", 10), Map.entry("overlayPosition", "bottom_right")));
        assertFalse(value.automaticRecording || value.remoteLive || value.remoteDownloads
                || value.automaticDeletion || value.overlayMaster || value.overlayDateTime
                || value.overlaySpeed || value.overlayAcceleration || value.overlayDriveState
                || value.overlayDecelerationRegen);
        assertTrue(value.recordParkedLive);
        assertEquals(VideoSettings.Quality.HIGH, value.quality);
        assertEquals(10L << 30, value.reserveBytes);
        assertEquals(VideoSettings.OverlayPosition.BOTTOM_RIGHT, value.overlayPosition);
    }
}
