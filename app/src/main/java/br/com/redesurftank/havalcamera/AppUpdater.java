package br.com.redesurftank.havalcamera;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** HTTP/worker adapter. Checking never downloads an APK; the UI owns consent and idle checks. */
public final class AppUpdater implements AutoCloseable {
    private static final String RELEASES = "https://api.github.com/repos/rafaelcs28/haval-camera/releases";
    public static final class Offer {
        public final UpdateManifest manifest;
        public final String url, notes;
        Offer(UpdateManifest manifest,String url,String notes) { this.manifest=manifest; this.url=url; this.notes=notes; }
    }
    public interface Callback { void checked(Offer offer); void failed(String reason); }
    public interface DownloadCallback { void verified(File apk); void failed(String reason); }
    private final OkHttpClient client;
    private final File cache;
    private final int installed;
    private final UpdateVerifier.ArchiveReader archives;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private volatile Call call;

    public AppUpdater(OkHttpClient client,File cache,int installed,UpdateVerifier.ArchiveReader archives) {
        this.client=client.newBuilder().followSslRedirects(false).connectTimeout(15,TimeUnit.SECONDS)
                .readTimeout(30,TimeUnit.SECONDS).callTimeout(5,TimeUnit.MINUTES).build();
        this.cache=cache; this.installed=installed; this.archives=archives;
    }
    public void check(boolean includePrerelease,Callback callback) {
        if (closed) return;
        worker.execute(() -> {
            try {
                UpdateManifest.Release release=UpdateManifest.selectRelease(readText(RELEASES
                        +(includePrerelease?"?per_page=100":"/latest"),2_097_152),includePrerelease);
                Offer offer=null;
                if (release!=null) {
                    UpdateManifest manifest=UpdateManifest.parse(readText(release.manifestUrl(),65536));
                    String apkUrl=release.apkUrl(manifest);
                    if (manifest.isNewerThan(installed)) offer=new Offer(manifest,apkUrl,release.notes);
                }
                if (!closed) callback.checked(offer);
            } catch (Exception error) { if (!closed) callback.failed("Não foi possível verificar atualização: " + error.getMessage()); }
        });
    }
    public void download(Offer offer,boolean confirmed,boolean idle,DownloadCallback callback) {
        UpdateDownload.requireConsent(confirmed,idle);
        if (closed) return;
        worker.execute(() -> {
            File apk=null; boolean delivered=false;
            try {
                File root=UpdateCache.resolve(cache,"update-probe.apk").getParentFile();
                if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cache indisponível");
                apk=File.createTempFile("update-", ".apk",root);
                try (Response response=get(offer.url,offer.manifest.size);
                     FileOutputStream output=new FileOutputStream(apk)) {
                    UpdateDownload.copy(response.body().byteStream(),output,offer.manifest.size);
                }
                requireVerified(apk,offer);
                if (!closed) { callback.verified(apk); delivered=true; }
            } catch (Exception error) {
                // Remove before reporting failure, so no callback can expose a partial APK.
                if (apk!=null && apk.exists() && !apk.delete()) {
                    if (!closed) callback.failed("Download rejeitado; não foi possível remover do cache");
                } else if (!closed) callback.failed("Download rejeitado: " + error.getMessage());
            } finally { if (!delivered && apk!=null) apk.delete(); }
        });
    }
    public void verifyAgain(File apk,Offer offer,DownloadCallback callback) {
        if (closed) return;
        worker.execute(() -> {
            try {
                requireVerified(apk,offer);
                if (!closed) callback.verified(apk); else apk.delete();
            } catch (Exception error) { if (!closed) callback.failed("APK rejeitado antes da instalação"); }
        });
    }
    private void requireVerified(File apk,Offer offer) throws IOException {
        UpdateVerifier.Verification result=UpdateVerifier.verify(apk,offer.manifest,archives);
        if (!result.valid) throw new IOException(result.reason);
    }
    private String readText(String url,long limit) throws IOException {
        try (Response response=get(url,limit); ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            UpdateDownload.copy(response.body().byteStream(),output,limit);
            return new String(output.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private Response get(String url,long limit) throws IOException {
        if (closed) throw new IOException("Cancelado");
        Call next=client.newCall(new Request.Builder().url(url).header("Accept","application/json, application/octet-stream")
                .header("User-Agent","Haval-Camera-Updater").build());
        call=next;
        if (closed) { next.cancel(); throw new IOException("Cancelado"); }
        Response response=next.execute();
        if (!response.isSuccessful() || response.body()==null || response.body().contentLength()>limit
                || !response.request().url().isHttps()) {
            response.close(); throw new IOException("Resposta HTTP inválida (" + response.code() + ")");
        }
        return response;
    }
    @Override public void close() {
        closed=true; Call active=call; if (active!=null) active.cancel(); worker.shutdownNow();
    }
}
