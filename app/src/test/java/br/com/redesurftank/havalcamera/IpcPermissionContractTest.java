package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class IpcPermissionContractTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test public void cameraOwnsAndRequestsTheExactControlPermissionUsedForResults() throws Exception {
        Path sourceRoot = Files.exists(Path.of("src/main/AndroidManifest.xml"))
                ? Path.of("src/main") : Path.of("app/src/main");
        Path manifestPath = sourceRoot.resolve("AndroidManifest.xml");
        Element manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(manifestPath.toFile()).getDocumentElement();

        assertTrue(hasPermission(manifest, "permission", VideoContract.CONTROL_PERMISSION));
        assertTrue(hasPermission(manifest, "uses-permission", VideoContract.CONTROL_PERMISSION));

        String service = new String(Files.readAllBytes(sourceRoot.resolve(
                "java/br/com/redesurftank/havalcamera/TripVideoService.java")),
                StandardCharsets.UTF_8);
        assertTrue(service.contains("sendBroadcast(result, VideoContract.CONTROL_PERMISSION);"));
        assertEquals("br.com.redesurftank.havalcamera.permission.CONTROL",
                VideoContract.CONTROL_PERMISSION);
    }

    private static boolean hasPermission(Element manifest, String tag, String expected) {
        NodeList values = manifest.getElementsByTagName(tag);
        for (int index = 0; index < values.getLength(); index++) {
            Element value = (Element) values.item(index);
            if (expected.equals(value.getAttributeNS(ANDROID, "name"))) return true;
        }
        return false;
    }
}
