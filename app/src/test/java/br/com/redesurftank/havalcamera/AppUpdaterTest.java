package br.com.redesurftank.havalcamera;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppUpdaterTest {
    @Test public void stableCheckUsesLatestAndDoesNotDownloadApk() throws Exception { check(false,2,true); }
    @Test public void optInUsesReleasesAndComparesInstalledCode() throws Exception { check(true,3,false); }
    private void check(boolean pre,int installed,boolean newer) throws Exception {
        List<String> urls = new CopyOnWriteArrayList<>();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            String url = chain.request().url().toString(); urls.add(url);
            String json = url.endsWith("update-manifest.json") ? UpdateManifestTest.JSON : UpdateManifestTest.release(pre,
                    UpdateManifestTest.asset("update-manifest.json")+","+UpdateManifestTest.asset("haval-camera.apk"));
            if (pre && !url.endsWith("update-manifest.json")) json="["+json+"]";
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create(json,MediaType.get("application/json"))).build();
        }).build();
        File cache = Files.createTempDirectory("updater-").toFile();
        try (AppUpdater updater=new AppUpdater(client,cache,installed,f -> null)) {
            CountDownLatch done=new CountDownLatch(1); List<AppUpdater.Offer> offers=new ArrayList<>(); List<String> errors=new ArrayList<>();
            updater.check(pre,new AppUpdater.Callback() {
                public void checked(AppUpdater.Offer offer) { offers.add(offer); done.countDown(); }
                public void failed(String error) { errors.add(error); done.countDown(); }
            });
            assertTrue(done.await(5,TimeUnit.SECONDS)); assertTrue(errors.toString(),errors.isEmpty());
            assertEquals(1,offers.size()); assertEquals(newer,offers.get(0)!=null);
            assertEquals(Arrays.asList("https://api.github.com/repos/rafaelcs28/haval-camera/releases"+(pre?"?per_page=100":"/latest"),
                    "https://github.com/rafaelcs28/haval-camera/releases/download/v3/update-manifest.json"),urls);
            assertEquals(0,cache.list().length);
        } finally { cache.delete(); }
    }
    @Test public void downloadRequiresConsentAndIdleBeforeNetwork() throws Exception {
        OkHttpClient client=new OkHttpClient.Builder().addInterceptor(chain -> { throw new AssertionError("Network before consent"); }).build();
        File cache=Files.createTempDirectory("updater-").toFile();
        try (AppUpdater updater=new AppUpdater(client,cache,2,f -> null)) {
            for (boolean[] flags : new boolean[][]{{false,true},{true,false}}) {
                try { updater.download(null,flags[0],flags[1],null); fail("Download accepted"); }
                catch (IllegalStateException expected) { }
            }
        } finally { cache.delete(); }
    }
    @Test public void downloadsAndVerifiesExactApk() throws Exception { download("abc",true); }
    @Test public void deletesCorruptDownload() throws Exception { download("abd",false); }
    @Test public void deletesOversizedDownload() throws Exception { download("abcd",false); }
    private void download(String bytes,boolean valid) throws Exception {
        List<String> urls=new CopyOnWriteArrayList<>();
        OkHttpClient client=new OkHttpClient.Builder().addInterceptor(chain -> {
            urls.add(chain.request().url().toString());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create(bytes,MediaType.get("application/octet-stream"))).build();
        }).build();
        File cache=Files.createTempDirectory("updater-").toFile();
        try (AppUpdater updater=new AppUpdater(client,cache,2,f -> new UpdateVerifier.Archive(
                "br.com.redesurftank.havalcamera",3,"0.3",UpdateVerifier.APPROVED_CERT))) {
            CountDownLatch done=new CountDownLatch(1); File[] received={null};
            AppUpdater.Offer offer=new AppUpdater.Offer(new UpdateManifest(3,"0.3","haval-camera.apk",3,UpdateManifestTest.HASH),
                    "https://github.com/rafaelcs28/haval-camera/releases/download/v3/haval-camera.apk","");
            updater.download(offer,true,true,new AppUpdater.DownloadCallback() {
                public void verified(File apk) { received[0]=apk; done.countDown(); }
                public void failed(String error) { done.countDown(); }
            });
            assertTrue(done.await(5,TimeUnit.SECONDS)); assertEquals(Arrays.asList(offer.url),urls);
            assertEquals(valid,received[0]!=null);
            try (java.util.stream.Stream<Path> files=Files.walk(cache.toPath())) {
                assertEquals(valid?1:0,files.filter(Files::isRegularFile).count());
            }
            if (valid) assertArrayEquals(new byte[]{97,98,99},Files.readAllBytes(received[0].toPath()));
        } finally {
            try (java.util.stream.Stream<Path> files=Files.walk(cache.toPath())) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
    @Test public void httpFailureDoesNotOfferUpdate() throws Exception {
        OkHttpClient client=new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder().request(chain.request())
                .protocol(Protocol.HTTP_1_1).code(403).message("Forbidden").body(ResponseBody.create("{}",MediaType.get("application/json"))).build()).build();
        File cache=Files.createTempDirectory("updater-").toFile();
        try (AppUpdater updater=new AppUpdater(client,cache,2,f -> null)) {
            CountDownLatch done=new CountDownLatch(1); boolean[] failed={false};
            updater.check(false,new AppUpdater.Callback() {
                public void checked(AppUpdater.Offer offer) { done.countDown(); }
                public void failed(String error) { failed[0]=true; done.countDown(); }
            });
            assertTrue(done.await(5,TimeUnit.SECONDS)); assertTrue(failed[0]);
        } finally { cache.delete(); }
    }
}
