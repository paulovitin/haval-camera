package br.com.redesurftank.havalcamera;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import java.security.MessageDigest;

/** Verifies Binder callers without granting a public Android permission. */
final class TrustedCallerVerifier {
    static void enforce(Context context) {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        PackageManager packages = context.getPackageManager();
        String[] names = packages.getPackagesForUid(uid);
        if (names != null) {
            for (String name : names) {
                if (!TrustedCallerPolicy.ECOTRIP_PACKAGE.equals(name)) continue;
                try {
                    PackageInfo info = packages.getPackageInfo(name,
                            Build.VERSION.SDK_INT >= 28
                                    ? PackageManager.GET_SIGNING_CERTIFICATES
                                    : PackageManager.GET_SIGNATURES);
                    Signature[] signatures = Build.VERSION.SDK_INT >= 28
                            ? info.signingInfo.getApkContentsSigners() : info.signatures;
                    if (signatures != null && signatures.length == 1
                            && TrustedCallerPolicy.allows(name, sha256(signatures[0].toByteArray()))) {
                        return;
                    }
                } catch (Exception ignored) { }
            }
        }
        throw new SecurityException("Caller is not an approved Haval Camera client");
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] hex = new char[digest.length * 2];
        for (int index = 0; index < digest.length; index++) {
            int item = digest[index] & 0xff;
            hex[index * 2] = alphabet[item >>> 4];
            hex[index * 2 + 1] = alphabet[item & 0x0f];
        }
        return new String(hex);
    }

    private TrustedCallerVerifier() { }
}
