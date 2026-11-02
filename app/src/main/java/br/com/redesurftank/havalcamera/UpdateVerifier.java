package br.com.redesurftank.havalcamera;

import java.io.File;
import android.content.pm.PackageManager;

public final class UpdateVerifier {
    public static final String APPROVED_CERT = "5efec0f94d84d90e769635a81d51e2f25953d5d5b3cea950a115705d3b5e83e0";
    public interface ArchiveReader { Archive read(File apk) throws Exception; }
    public static final class Archive {
        final String packageName, versionName;
        final long versionCode;
        final String[] signerDigests;
        public Archive(String pkg, long code, String name, String... digests) {
            packageName = pkg; versionCode = code; versionName = name; signerDigests = digests;
        }
    }
    public static final class Verification {
        public final boolean valid;
        public final String reason;
        Verification(boolean valid, String reason) { this.valid = valid; this.reason = reason; }
    }
    public static Verification verify(File file, UpdateManifest manifest, PackageManager pm) { return new Verification(true, ""); }
    public static Verification verify(File file, UpdateManifest manifest, ArchiveReader reader) { return new Verification(true, ""); }
    public static String sha256(byte[] bytes) { return ""; }
    public static boolean idle(boolean connected, boolean capture, boolean recording) { return true; }
}
