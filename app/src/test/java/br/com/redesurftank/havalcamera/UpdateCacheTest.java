package br.com.redesurftank.havalcamera;
import java.io.*;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;
public class UpdateCacheTest {
    @Test public void resolvesOnlySingleApkWithinUpdatesCache() throws Exception {
        File cache = Files.createTempDirectory("ota-cache-").toFile();
        try {
            assertEquals(new File(cache,"updates/update-123.apk").getCanonicalFile(),UpdateCache.resolve(cache,"update-123.apk"));
            for (String path : new String[]{"../secret.apk","/secret.apk","other.txt","","sub/a.apk",".."}) {
                try { UpdateCache.resolve(cache,path); fail("Escaping filename accepted"); } catch (IOException expected) { }
            }
        } finally { cache.delete(); }
    }
    @Test public void refusesWriteModes() throws Exception {
        UpdateCache.requireReadOnly("r");
        for (String mode : new String[]{"w","rw","rwt","wa"}) {
            try { UpdateCache.requireReadOnly(mode); fail("Writable grant accepted"); } catch (FileNotFoundException expected) { }
        }
    }
}
