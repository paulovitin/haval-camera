package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class NativeCaptureCompatibilityTest {
    @Test public void hookAcceptsObservedBinaryAndUpdateWriteModes() throws Exception {
        Path source = Files.exists(Path.of("src/main/cpp/camera_stream.c"))
                ? Path.of("src/main/cpp/camera_stream.c")
                : Path.of("app/src/main/cpp/camera_stream.c");
        String body = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

        assertTrue(body.contains("is_dump_write_mode(mode)"));
        for (String mode : new String[]{"w", "wb", "w+", "wb+", "w+b"}) {
            assertTrue("missing supported fopen mode " + mode,
                    body.contains("strcmp(mode, \"" + mode + "\")"));
        }
    }
}
