package br.com.redesurftank.havalcamera;

/** Immutable update metadata; implementation follows the RED queue run. */
public final class UpdateManifest {
    public final int versionCode;
    public final String versionName, apk, sha256;
    public final long size;
    UpdateManifest(int code, String name, String apk, long size, String hash) {
        this.versionCode = code; this.versionName = name; this.apk = apk; this.size = size; this.sha256 = hash;
    }
    public static UpdateManifest parse(String json) { return null; }
    public boolean isNewerThan(int installed) { return false; }
    public static Release selectRelease(String json, boolean includePrerelease) { return null; }
    public static final class Release {
        public final String notes = "";
        public String manifestUrl() { return null; }
        public String apkUrl(UpdateManifest manifest) { return null; }
    }
}
