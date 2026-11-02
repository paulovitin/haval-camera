package br.com.redesurftank.havalcamera;

import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
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
        Consumer<byte[]> encoded; List<Consumer<byte[]>> targets;
        synchronized (this) { encoded = encoder; targets = new ArrayList<>(previews); }
        if (encoded != null) encoded.accept(frame);
        for (Consumer<byte[]> target : targets) target.accept(frame);
    }
    public synchronized void closePreviews() { }
    public synchronized int previewSubscriberCount() { return previews.size(); }
}
