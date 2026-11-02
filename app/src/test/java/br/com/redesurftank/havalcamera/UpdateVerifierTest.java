package br.com.redesurftank.havalcamera;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateVerifierTest {
    private final UpdateManifest manifest = new UpdateManifest(3,"0.3","haval-camera.apk",3,UpdateManifestTest.HASH);
    private UpdateVerifier.Archive good() {
        return new UpdateVerifier.Archive("br.com.redesurftank.havalcamera",3,"0.3",UpdateVerifier.APPROVED_CERT);
    }
    private File file(String value) throws Exception {
        File file = File.createTempFile("update-", ".apk"); Files.write(file.toPath(),value.getBytes(StandardCharsets.UTF_8)); return file;
    }
    @Test public void acceptsExactBytesAndApprovedArchive() throws Exception {
        File file = file("abc");
        try { assertTrue(UpdateVerifier.verify(file,manifest,f -> good()).valid); assertTrue(file.exists()); }
        finally { file.delete(); }
    }
    @Test public void hashesCertificateBytesWithSha256() {
        assertEquals(UpdateManifestTest.HASH, UpdateVerifier.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void deletesWrongSizeAndHashBeforeInspectingSigner() throws Exception {
        for (String bytes : new String[]{"ab", "abcd", "abd"}) {
            File file = file(bytes);
            try {
                assertFalse(UpdateVerifier.verify(file,manifest,f -> { fail("Read archive before checking bytes"); return null; }).valid);
                assertFalse(file.exists());
            } finally { file.delete(); }
        }
    }
    @Test public void deletesWrongMissingMultipleSignerAndMismatchedPackageVersion() throws Exception {
        for (UpdateVerifier.Archive archive : new UpdateVerifier.Archive[]{null,
                new UpdateVerifier.Archive("br.com.redesurftank.havalcamera",3,"0.3","wrong"),
                new UpdateVerifier.Archive("br.com.redesurftank.havalcamera",3,"0.3"),
                new UpdateVerifier.Archive("br.com.redesurftank.havalcamera",3,"0.3",UpdateVerifier.APPROVED_CERT,"wrong"),
                new UpdateVerifier.Archive("other.app",3,"0.3",UpdateVerifier.APPROVED_CERT),
                new UpdateVerifier.Archive("br.com.redesurftank.havalcamera",2,"0.3",UpdateVerifier.APPROVED_CERT),
                new UpdateVerifier.Archive("br.com.redesurftank.havalcamera",3,"wrong",UpdateVerifier.APPROVED_CERT)}) {
            File file = file("abc");
            try { assertFalse(UpdateVerifier.verify(file,manifest,f -> archive).valid); assertFalse(file.exists()); }
            finally { file.delete(); }
        }
    }
    @Test public void deletesWhenArchiveReaderFails() throws Exception {
        File file = file("abc");
        try { assertFalse(UpdateVerifier.verify(file,manifest,f -> { throw new SecurityException("corrupt"); }).valid); assertFalse(file.exists()); }
        finally { file.delete(); }
    }
    @Test public void android9ArchiveLookupCollectsAndExposesCurrentCertificates() {
        File apk = new File("update.apk");
        assertNull(UpdateVerifier.inspectArchive(apk, (path, flags) -> {
            assertEquals(apk.getAbsolutePath(), path);
            // Android 9 r1 collects/validates certificates only when GET_SIGNATURES is set.
            assertTrue("Android 9 must collect certificates", (flags & 0x40) != 0);
            assertTrue("Return current signer data", (flags & 0x08000000) != 0);
            return null;
        }));
    }
    @Test public void requiresConnectedIdleService() {
        assertTrue(UpdateVerifier.idle(true,false,false));
        assertFalse(UpdateVerifier.idle(false,false,false));
        assertFalse(UpdateVerifier.idle(true,true,false));
        assertFalse(UpdateVerifier.idle(true,false,true));
        assertFalse(UpdateVerifier.idle(true,true,true));
    }
}
