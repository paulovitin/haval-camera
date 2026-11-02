package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import br.com.redesurftank.havalcamera.TelemetryOverlayModel.Position;
import br.com.redesurftank.havalcamera.TelemetryOverlayModel.Settings;
import br.com.redesurftank.havalcamera.TelemetryOverlayModel.Snapshot;
import org.junit.Test;

public final class TelemetryOverlayModelTest {
    private static final int BOTH_VALID = VideoContract.TELEMETRY_VALID_SPEED
            | VideoContract.TELEMETRY_VALID_MOTOR_POWER;

    @Test
    public void speedChangesUseBoundedLowPassAcceleration() {
        TelemetryOverlayModel model = new TelemetryOverlayModel();
        assertTrue(model.acceptSample(1_000, 10_000, 0f, 10f, BOTH_VALID, 1_000));
        assertTrue(model.acceptSample(2_000, 11_000, 36f, 10f, BOTH_VALID, 2_000));

        Snapshot snapshot = model.snapshot(2_000, 11_000, Settings.all(Position.BOTTOM_LEFT));

        assertEquals(2.5f, snapshot.accelerationMps2, 0.001f);

        assertTrue(model.acceptSample(2_100, 11_100, 300f, 10f, BOTH_VALID, 2_100));
        assertTrue(Math.abs(model.snapshot(2_100, 11_100, Settings.all(Position.BOTTOM_LEFT))
                .accelerationMps2) <= 12f);
    }

    @Test
    public void staleAndFutureSamplesHideOrRejectDynamicValues() {
        TelemetryOverlayModel model = new TelemetryOverlayModel();
        assertTrue(model.acceptSample(1_000, 10_000, 50f, -5f, BOTH_VALID, 1_000));

        Snapshot stale = model.snapshot(3_001, 12_001, Settings.all(Position.TOP_LEFT));

        assertNull(stale.speedKmh);
        assertNull(stale.accelerationMps2);
        assertNull(stale.driveState);
        assertNull(stale.decelerationState);
        assertEquals(Long.valueOf(12_001), stale.wallClockMs);
        assertFalse(model.acceptSample(4_001, 13_001, 50f, 5f, BOTH_VALID, 4_000));
    }

    @Test
    public void malformedAndOutOfBoundsSamplesAreRejected() {
        TelemetryOverlayModel model = new TelemetryOverlayModel();

        assertFalse(model.acceptSample(-1, 1, 1f, 1f, BOTH_VALID, 1));
        assertFalse(model.acceptSample(1, -1, 1f, 1f, BOTH_VALID, 1));
        assertFalse(model.acceptSample(1, 1, -0.1f, 1f, BOTH_VALID, 1));
        assertFalse(model.acceptSample(1, 1, 300.1f, 1f, BOTH_VALID, 1));
        assertFalse(model.acceptSample(1, 1, 1f, -500.1f, BOTH_VALID, 1));
        assertFalse(model.acceptSample(1, 1, 1f, 500.1f, BOTH_VALID, 1));
        assertFalse(model.acceptSample(1, 1, Float.NaN, 1f, BOTH_VALID, 1));
        assertFalse(model.acceptSample(1, 1, 1f, Float.POSITIVE_INFINITY, BOTH_VALID, 1));
        assertFalse(model.acceptSample(1, 1, 1f, 1f, 4, 1));
    }

    @Test
    public void classifiesAccelerationConstantDecelerationAndRegenHonestly() {
        assertEquals(TelemetryOverlayModel.DriveState.ACCELERATING,
                transition(0f, 18f, 10f).driveState);
        assertEquals(TelemetryOverlayModel.DriveState.CONSTANT,
                transition(18f, 18f, 10f).driveState);
        assertEquals(TelemetryOverlayModel.DecelerationState.DECELERATING,
                transition(36f, 18f, 10f).decelerationState);
        Snapshot regen = transition(36f, 18f, -1f);
        assertEquals(TelemetryOverlayModel.DecelerationState.REGENERATING,
                regen.decelerationState);
        assertFalse(regen.decelerationLabel.contains("FREIO"));
        assertFalse(regen.decelerationLabel.contains("PEDAL"));
    }

    @Test
    public void masterAndEveryFieldSwitchAreIndependent() {
        TelemetryOverlayModel model = populatedModel();
        Settings all = Settings.all(Position.BOTTOM_RIGHT);

        Snapshot off = model.snapshot(2_000, 11_000, all.withMaster(false));
        assertNull(off.wallClockMs);
        assertNull(off.speedKmh);
        assertNull(off.accelerationMps2);
        assertNull(off.driveState);
        assertNull(off.decelerationState);

        assertNull(model.snapshot(2_000, 11_000, all.withDateTime(false)).wallClockMs);
        assertNull(model.snapshot(2_000, 11_000, all.withSpeed(false)).speedKmh);
        assertNull(model.snapshot(2_000, 11_000, all.withAcceleration(false)).accelerationMps2);
        assertNull(model.snapshot(2_000, 11_000, all.withDriveState(false)).driveState);
        assertNull(model.snapshot(2_000, 11_000, all.withDeceleration(false)).decelerationState);

        Snapshot enabled = model.snapshot(2_000, 11_000, all);
        assertEquals(Long.valueOf(11_000), enabled.wallClockMs);
        assertEquals(Float.valueOf(18f), enabled.speedKmh);
        assertTrue(enabled.accelerationMps2 > 0f);
        assertEquals(TelemetryOverlayModel.DriveState.ACCELERATING, enabled.driveState);
    }

    @Test
    public void everyCornerPositionIsPreserved() {
        TelemetryOverlayModel model = populatedModel();
        for (Position position : Position.values()) {
            assertEquals(position,
                    model.snapshot(2_000, 11_000, Settings.all(position)).position);
        }
        assertEquals(4, Position.values().length);
    }

    private static Snapshot transition(float firstSpeed, float secondSpeed, float motorPower) {
        TelemetryOverlayModel model = new TelemetryOverlayModel();
        model.acceptSample(1_000, 10_000, firstSpeed, motorPower, BOTH_VALID, 1_000);
        model.acceptSample(2_000, 11_000, secondSpeed, motorPower, BOTH_VALID, 2_000);
        return model.snapshot(2_000, 11_000, Settings.all(Position.BOTTOM_LEFT));
    }

    private static TelemetryOverlayModel populatedModel() {
        TelemetryOverlayModel model = new TelemetryOverlayModel();
        model.acceptSample(1_000, 10_000, 0f, 5f, BOTH_VALID, 1_000);
        model.acceptSample(2_000, 11_000, 18f, 5f, BOTH_VALID, 2_000);
        return model;
    }
}
