package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class BuildSmokeTest {
    @Test
    public void existingFrameSizeIsStable() {
        assertEquals(1760, CameraPreview.WIDTH);
        assertEquals(720, CameraPreview.HEIGHT);
        assertEquals(3_801_600, CameraPreview.FRAME_SIZE);
    }
}
