package br.com.redesurftank.havalcamera;
import java.io.File;
import okhttp3.OkHttpClient;
public final class AppUpdater implements AutoCloseable {
    public static final class Offer {
        public final UpdateManifest manifest;
        public final String url, notes;
        Offer(UpdateManifest manifest,String url,String notes) { this.manifest=manifest; this.url=url; this.notes=notes; }
    }
    public interface Callback { void checked(Offer offer); void failed(String reason); }
    public interface DownloadCallback { void verified(File apk); void failed(String reason); }
    public AppUpdater(OkHttpClient client,File cache,int installed,UpdateVerifier.ArchiveReader archives) { }
    public void check(boolean includePrerelease,Callback callback) { callback.checked(null); }
    public void download(Offer offer,boolean confirmed,boolean idle,DownloadCallback callback) { callback.failed("Not implemented"); }
    @Override public void close() { }
}
