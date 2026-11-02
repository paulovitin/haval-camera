package br.com.redesurftank.havalcamera;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdatePublicationTest {
    @Test public void derivesManifestFromVerifiedApk() throws Exception { run(false,false); }
    @Test public void refusesWrongCertificate() throws Exception { run(true,false); }
    @Test public void refusesDifferentPackage() throws Exception { run(false,true); }
    private void run(boolean badCert, boolean badPackage) throws Exception {
        File script = new File("../scripts/write-update-manifest.sh");
        if (!script.isFile()) script = new File("scripts/write-update-manifest.sh");
        assertTrue("Publication script missing",script.isFile());
        Path dir = Files.createTempDirectory("ota-publication-");
        try {
            Path apk = dir.resolve("haval-camera.apk"), result = dir.resolve("update-manifest.json");
            Files.write(apk,"abc".getBytes(StandardCharsets.UTF_8));
            executable(dir.resolve("apksigner"),"echo 'Signer #1 certificate SHA-256 digest: "+(badCert?"bad":UpdateVerifier.APPROVED_CERT)+"'\n");
            executable(dir.resolve("zipalign"),"exit 0\n"); executable(dir.resolve("unzip"),"exit 0\n");
            executable(dir.resolve("aapt"),"echo \"package: name='"+(badPackage?"other.app":"br.com.redesurftank.havalcamera")+"' versionCode='3' versionName='0.3'\"\n");
            ProcessBuilder pb = new ProcessBuilder("bash",script.getCanonicalPath(),apk.toString(),result.toString(),dir.toString());
            pb.environment().put("PATH",dir+File.pathSeparator+System.getenv("PATH"));
            pb.redirectErrorStream(true); Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            int rc = process.waitFor();
            if (badCert || badPackage) { assertNotEquals(output,0,rc); assertFalse(Files.exists(result)); }
            else {
                assertEquals(output,0,rc);
                UpdateManifest manifest = UpdateManifest.parse(new String(Files.readAllBytes(result),StandardCharsets.UTF_8));
                assertEquals(3,manifest.versionCode); assertEquals("0.3",manifest.versionName);
                assertEquals(3,manifest.size); assertEquals(UpdateManifestTest.HASH,manifest.sha256);
                assertEquals("haval-camera.apk",manifest.apk);
            }
        } finally {
            try (java.util.stream.Stream<Path> files=Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
    private void executable(Path path,String body) throws Exception {
        Files.write(path,("#!/bin/sh\n"+body).getBytes(StandardCharsets.UTF_8)); assertTrue(path.toFile().setExecutable(true));
    }
}
