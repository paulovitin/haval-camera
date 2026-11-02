package br.com.redesurftank.havalcamera;

import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateManifestTest {
    static final String HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    static final String JSON = "{\"schemaVersion\":1,\"versionCode\":3,\"versionName\":\"0.3\",\"apk\":\"haval-camera.apk\",\"size\":3,\"sha256\":\"" + HASH + "\"}";
    static String asset(String name) {
        return "{\"name\":\""+name+"\",\"browser_download_url\":\"https://github.com/rafaelcs28/haval-camera/releases/download/v3/"+name+"\"}";
    }
    static String release(boolean pre, String assets) {
        return "{\"draft\":false,\"prerelease\":"+pre+",\"body\":\"Notas\\ncurtas\",\"assets\":["+assets+"]}";
    }
    @Test public void ordersOnlyByVersionCode() {
        UpdateManifest m = UpdateManifest.parse(JSON); assertNotNull(m);
        assertTrue(m.isNewerThan(2)); assertFalse(m.isNewerThan(3)); assertFalse(m.isNewerThan(4));
        assertEquals("0.3", m.versionName); assertEquals(3, m.size);
    }
    @Test public void rejectsUnknownSchemaMissingDuplicateAndExtraFields() {
        for (String json : new String[]{JSON.replace("schemaVersion\":1", "schemaVersion\":2"),
                JSON.replace("\"schemaVersion\":1,", ""), JSON.replace("\"apk\":", "\"apk\":\"other.apk\",\"apk\":"),
                JSON.replace("{", "{\"extra\":0,"), JSON + "{}", JSON.replace("3,", "3.0,")}) reject(json);
    }
    @Test public void rejectsInvalidSizesHashesAndPaths() {
        for (String json : new String[]{JSON.replace("size\":3", "size\":0"), JSON.replace("size\":3", "size\":-1"),
                JSON.replace("size\":3", "size\":1073741825"), JSON.replace(HASH, HASH.toUpperCase()),
                JSON.replace(HASH, "abc"), JSON.replace("haval-camera.apk", "../bad.apk"),
                JSON.replace("versionCode\":3", "versionCode\":2147483648"), JSON.replace("\"0.3\"", "\"\"")}) reject(json);
    }
    @Test public void rejectsMalformedJsonAndWrongTypes() {
        for (String json : new String[]{"null", "[]", JSON.replace("size\":3", "size\":\"3\""),
                JSON.replace("size\":3", "size\":03"), JSON.replace("size\":3", "size\":3e0"),
                JSON.replace("0.3", "bad\nname"), JSON.replace("}", ",}"), JSON.substring(1)}) reject(json);
    }
    @Test public void stableExcludesPrereleaseAndDraft() {
        String pre = release(true, asset("update-manifest.json"));
        String stable = release(false, asset("update-manifest.json"));
        assertNull(UpdateManifest.selectRelease(pre, false));
        assertNull(UpdateManifest.selectRelease(stable.replace("draft\":false", "draft\":true"), true));
        assertNotNull(UpdateManifest.selectRelease("["+pre+","+stable+"]", false));
        assertNotNull(UpdateManifest.selectRelease("["+pre+"]", true));
    }
    @Test public void selectsExactApkEvenWithOtherApksPresent() {
        UpdateManifest.Release r = UpdateManifest.selectRelease(release(false,
                asset("other.apk")+","+asset("update-manifest.json")+","+asset("haval-camera.apk")), false);
        assertNotNull(r);
        assertEquals("https://github.com/rafaelcs28/haval-camera/releases/download/v3/haval-camera.apk", r.apkUrl(UpdateManifest.parse(JSON)));
    }
    @Test public void rejectsMissingAndDuplicateAssets() {
        for (String assets : new String[]{asset("other.apk"), asset("update-manifest.json")+","+asset("update-manifest.json"),
                asset("update-manifest.json"), asset("update-manifest.json")+","+asset("haval-camera.apk")+","+asset("haval-camera.apk")}) {
            try {
                UpdateManifest.Release r = UpdateManifest.selectRelease(release(false, assets), false);
                assertNotNull(r); r.manifestUrl(); r.apkUrl(new UpdateManifest(3,"0.3","haval-camera.apk",3,HASH));
                fail("Ambiguous/missing asset accepted");
            } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void rejectsAssetsOutsideOfficialHttpsRepository() {
        for (String replacement : new String[]{"http://github.com", "https://evil.example", "https://github.com@evil.example"}) {
            try {
                UpdateManifest.Release r = UpdateManifest.selectRelease(release(false, asset("update-manifest.json")).replace("https://github.com", replacement), false);
                assertNotNull(r); r.manifestUrl(); fail("Untrusted URL accepted");
            } catch (IllegalArgumentException expected) { }
        }
    }
    private static void reject(String json) {
        try { UpdateManifest.parse(json); fail("Invalid manifest accepted: " + json); }
        catch (IllegalArgumentException expected) { }
    }
}
