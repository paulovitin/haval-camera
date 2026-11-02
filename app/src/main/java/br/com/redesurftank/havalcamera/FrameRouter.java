package br.com.redesurftank.havalcamera;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Service-owned fanout for one capture stream. */
public final class FrameRouter {
    public interface Subscription extends AutoCloseable { @Override void close(); }
    private final Set<Consumer<byte[]>> previews = new HashSet<>();
    private Consumer<byte[]> encoder;
    public synchronized void setEncoder(Consumer<byte[]> destination) { encoder = destination; }
    public synchronized Subscription subscribe(Consumer<byte[]> destination) {
        previews.add(destination); return () -> { synchronized (FrameRouter.this) { previews.remove(destination); } };
    }
    public void publish(byte[] frame) {
        Consumer<byte[]> encoded; Consumer<byte[]>[] targets;
        synchronized (this) { encoded = encoder; targets = previews.toArray(new Consumer[0]); }
        if (encoded != null) encoded.accept(frame);
        for (Consumer<byte[]> target : targets) target.accept(frame);
    }
    public synchronized int previewSubscriberCount() { return previews.size(); }
}
