package br.com.redesurftank.havalcamera;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/** Byte validation precedes Android archive inspection; only current single signer is trusted. */
public final class UpdateVerifier {
    public static final String APPROVED_CERT = "5efec0f94d84d90e769635a81d51e2f25953d5d5b3cea950a115705d3b5e83e0";
    public static final String PACKAGE = "br.com.redesurftank.havalcamera";
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
    public static Verification verify(File file, UpdateManifest manifest, PackageManager pm) {
        return verify(file, manifest, apk -> inspectArchive(apk, pm));
    }
    static Archive inspectArchive(File apk, PackageManager pm) {
        return inspectArchive(apk, pm::getPackageArchiveInfo);
    }
    static Archive inspectArchive(File apk, java.util.function.BiFunction<String,Integer,PackageInfo> lookup) {
        PackageInfo info = lookup.apply(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (info == null || info.signingInfo == null) return null;
        Signature[] signers = info.signingInfo.getApkContentsSigners();
        if (signers == null) return null;
        String[] digests = new String[signers.length];
        for (int i=0; i<signers.length; i++) digests[i] = sha256(signers[i].toByteArray());
        return new Archive(info.packageName, info.getLongVersionCode(), info.versionName, digests);
    }
    public static Verification verify(File file, UpdateManifest manifest, ArchiveReader reader) {
        try {
            if (!file.isFile() || file.length() != manifest.size) throw new IllegalArgumentException("Tamanho do APK incorreto");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[32768]; int count; long total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count; if (total > manifest.size) throw new IllegalArgumentException("APK excedeu tamanho");
                    digest.update(buffer,0,count);
                }
                if (total != manifest.size) throw new IllegalArgumentException("APK incompleto");
            }
            if (!hex(digest.digest()).equals(manifest.sha256)) throw new IllegalArgumentException("SHA-256 do APK incorreto");
            Archive archive = reader.read(file);
            if (archive == null || !PACKAGE.equals(archive.packageName) || archive.versionCode != manifest.versionCode
                    || !manifest.versionName.equals(archive.versionName) || archive.signerDigests == null
                    || archive.signerDigests.length != 1 || !APPROVED_CERT.equals(archive.signerDigests[0]))
                throw new IllegalArgumentException("Identidade, versão ou certificado do APK incorreto");
            return new Verification(true, "");
        } catch (Exception failure) {
            boolean deleted = !file.exists() || file.delete();
            return new Verification(false, deleted ? "APK rejeitado e removido" : "APK rejeitado; não foi possível remover do cache");
        }
    }
    public static String sha256(byte[] bytes) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(Character.forDigit((value >>> 4) & 15,16)).append(Character.forDigit(value & 15,16));
        return out.toString();
    }
    public static boolean idle(boolean connected, boolean capture, boolean recording) { return connected && !capture && !recording; }
}
