package br.com.redesurftank.havalcamera;

import java.util.Objects;

/** Validates telemetry and publishes immutable display state derived only from numeric samples. */
public final class TelemetryOverlayModel {
    private static final float MAX_SPEED_KMH = 300f;
    private static final float MAX_MOTOR_POWER_KW = 500f;
    private static final float MAX_ACCELERATION_MPS2 = 12f;
    private static final float FILTER_ALPHA = 0.25f;
    private static final float MOVING_THRESHOLD_MPS2 = 0.2f;

    private long monotonicMs = -1;
    private long wallClockMs;
    private Float speedKmh;
    private Float motorPowerKw;
    private Float filteredAccelerationMps2;

    public synchronized boolean acceptSample(
            long sampleMonotonicMs,
            long sampleWallClockMs,
            float sampleSpeedKmh,
            float sampleMotorPowerKw,
            int validityFlags,
            long nowMonotonicMs) {
        int knownFlags = VideoContract.TELEMETRY_VALID_SPEED
                | VideoContract.TELEMETRY_VALID_MOTOR_POWER;
        if (sampleMonotonicMs < 0 || sampleWallClockMs < 0
                || (validityFlags & ~knownFlags) != 0
                || !VideoContract.isFreshTelemetrySample(sampleMonotonicMs, nowMonotonicMs)) {
            return false;
        }
        boolean speedValid = (validityFlags & VideoContract.TELEMETRY_VALID_SPEED) != 0;
        boolean powerValid = (validityFlags & VideoContract.TELEMETRY_VALID_MOTOR_POWER) != 0;
        if (speedValid && (!Float.isFinite(sampleSpeedKmh)
                || sampleSpeedKmh < 0 || sampleSpeedKmh > MAX_SPEED_KMH)) return false;
        if (powerValid && (!Float.isFinite(sampleMotorPowerKw)
                || Math.abs(sampleMotorPowerKw) > MAX_MOTOR_POWER_KW)) return false;
        if (monotonicMs >= 0 && sampleMonotonicMs <= monotonicMs) return false;

        Float nextAcceleration = null;
        if (speedValid && speedKmh != null && monotonicMs >= 0) {
            float elapsedSeconds = (sampleMonotonicMs - monotonicMs) / 1_000f;
            float raw = ((sampleSpeedKmh - speedKmh) / 3.6f) / elapsedSeconds;
            raw = clamp(raw, -MAX_ACCELERATION_MPS2, MAX_ACCELERATION_MPS2);
            float previous = filteredAccelerationMps2 == null ? 0f : filteredAccelerationMps2;
            nextAcceleration = previous + FILTER_ALPHA * (raw - previous);
        } else if (speedValid) {
            nextAcceleration = 0f;
        }

        monotonicMs = sampleMonotonicMs;
        wallClockMs = sampleWallClockMs;
        speedKmh = speedValid ? sampleSpeedKmh : null;
        motorPowerKw = powerValid ? sampleMotorPowerKw : null;
        filteredAccelerationMps2 = nextAcceleration;
        return true;
    }

    public synchronized Snapshot snapshot(
            long nowMonotonicMs, long nowWallClockMs, Settings settings) {
        Objects.requireNonNull(settings, "settings");
        boolean fresh = monotonicMs >= 0
                && VideoContract.isFreshTelemetrySample(monotonicMs, nowMonotonicMs);
        if (!settings.master) return new Snapshot(settings.position, null, null, null, null, null);

        Float acceleration = fresh && settings.acceleration ? filteredAccelerationMps2 : null;
        DriveState driveState = null;
        DecelerationState decelerationState = null;
        if (fresh && filteredAccelerationMps2 != null) {
            if (filteredAccelerationMps2 > MOVING_THRESHOLD_MPS2) {
                driveState = DriveState.ACCELERATING;
            } else if (filteredAccelerationMps2 >= -MOVING_THRESHOLD_MPS2) {
                driveState = DriveState.CONSTANT;
            } else {
                decelerationState = motorPowerKw != null && motorPowerKw < 0
                        ? DecelerationState.REGENERATING : DecelerationState.DECELERATING;
            }
        }
        return new Snapshot(
                settings.position,
                settings.dateTime ? nowWallClockMs : null,
                fresh && settings.speed ? speedKmh : null,
                acceleration,
                settings.driveState ? driveState : null,
                settings.deceleration ? decelerationState : null);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public enum Position {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    public enum DriveState {
        ACCELERATING("ACEL."), CONSTANT("CONST.");

        final String label;

        DriveState(String label) {
            this.label = label;
        }
    }

    public enum DecelerationState {
        DECELERATING("DESACEL."), REGENERATING("REGEN");

        final String label;

        DecelerationState(String label) {
            this.label = label;
        }
    }

    public static final class Snapshot {
        public final Position position;
        public final Long wallClockMs;
        public final Float speedKmh;
        public final Float accelerationMps2;
        public final DriveState driveState;
        public final DecelerationState decelerationState;
        public final String driveLabel;
        public final String decelerationLabel;

        private Snapshot(
                Position position,
                Long wallClockMs,
                Float speedKmh,
                Float accelerationMps2,
                DriveState driveState,
                DecelerationState decelerationState) {
            this.position = position;
            this.wallClockMs = wallClockMs;
            this.speedKmh = speedKmh;
            this.accelerationMps2 = accelerationMps2;
            this.driveState = driveState;
            this.decelerationState = decelerationState;
            this.driveLabel = driveState == null ? "" : driveState.label;
            this.decelerationLabel = decelerationState == null ? "" : decelerationState.label;
        }
    }

    public static final class Settings {
        public final boolean master;
        public final boolean dateTime;
        public final boolean speed;
        public final boolean acceleration;
        public final boolean driveState;
        public final boolean deceleration;
        public final Position position;

        public Settings(
                boolean master,
                boolean dateTime,
                boolean speed,
                boolean acceleration,
                boolean driveState,
                boolean deceleration,
                Position position) {
            this.master = master;
            this.dateTime = dateTime;
            this.speed = speed;
            this.acceleration = acceleration;
            this.driveState = driveState;
            this.deceleration = deceleration;
            this.position = Objects.requireNonNull(position, "position");
        }

        public static Settings all(Position position) {
            return new Settings(true, true, true, true, true, true, position);
        }

        public Settings withMaster(boolean value) {
            return copy(value, dateTime, speed, acceleration, driveState, deceleration);
        }

        public Settings withDateTime(boolean value) {
            return copy(master, value, speed, acceleration, driveState, deceleration);
        }

        public Settings withSpeed(boolean value) {
            return copy(master, dateTime, value, acceleration, driveState, deceleration);
        }

        public Settings withAcceleration(boolean value) {
            return copy(master, dateTime, speed, value, driveState, deceleration);
        }

        public Settings withDriveState(boolean value) {
            return copy(master, dateTime, speed, acceleration, value, deceleration);
        }

        public Settings withDeceleration(boolean value) {
            return copy(master, dateTime, speed, acceleration, driveState, value);
        }

        private Settings copy(
                boolean nextMaster,
                boolean nextDateTime,
                boolean nextSpeed,
                boolean nextAcceleration,
                boolean nextDriveState,
                boolean nextDeceleration) {
            return new Settings(nextMaster, nextDateTime, nextSpeed, nextAcceleration,
                    nextDriveState, nextDeceleration, position);
        }
    }
}
