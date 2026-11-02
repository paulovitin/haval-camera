package br.com.redesurftank.havalcamera;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;

/** Freshness-first queue for live video. It never blocks the codec or recorder. */
public final class BoundedFrameQueue {
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_MESSAGES = 30;

    public static final class Message {
        private final byte[] data;
        public final boolean config;
        public final boolean keyframe;

        private Message(byte[] data, boolean config, boolean keyframe) {
            this.data = Arrays.copyOf(data, data.length);
            this.config = config;
            this.keyframe = keyframe;
        }

        public byte[] bytes() {
            return Arrays.copyOf(data, data.length);
        }

        int size() {
            return data.length;
        }
    }

    private final int maxBytes;
    private final int maxMessages;
    private final Runnable requestSync;
    private final Deque<Message> messages = new ArrayDeque<>();
    private int bytes;
    private boolean recovering;
    private boolean syncRequested;

    public BoundedFrameQueue(Runnable requestSync) {
        this(MAX_BYTES, MAX_MESSAGES, requestSync);
    }

    BoundedFrameQueue(int maxBytes, int maxMessages, Runnable requestSync) {
        if (maxBytes <= 0 || maxMessages <= 0) throw new IllegalArgumentException("Positive limits required");
        this.maxBytes = maxBytes;
        this.maxMessages = maxMessages;
        this.requestSync = Objects.requireNonNull(requestSync, "requestSync");
    }

    public synchronized boolean offerConfig(byte[] data) {
        return offerConfig(data, 0);
    }

    public synchronized boolean offerConfig(byte[] data, long transportBytes) {
        Message message = message(data, true, false);
        if (!validTransportBytes(transportBytes) || message.size() + transportBytes > maxBytes) {
            return false;
        }
        messages.clear();
        bytes = 0;
        messages.addFirst(message);
        bytes += message.size();
        enterRecovery();
        return true;
    }

    public synchronized boolean offerAccessUnit(byte[] data, boolean keyframe) {
        return offerAccessUnit(data, keyframe, 0);
    }

    public synchronized boolean offerAccessUnit(byte[] data, boolean keyframe, long transportBytes) {
        Message message = message(data, false, keyframe);
        if (!validTransportBytes(transportBytes)) return false;
        if (keyframe) {
            removeWhere(existing -> !existing.config);
            if (!fits(message, transportBytes)) {
                enterRecovery();
                return false;
            }
            messages.addLast(message);
            bytes += message.size();
            recovering = false;
            syncRequested = false;
            return true;
        }
        if (recovering || !fits(message, transportBytes)) {
            enterRecovery();
            return false;
        }
        messages.addLast(message);
        bytes += message.size();
        return true;
    }

    public synchronized Message poll() {
        Message result = messages.pollFirst();
        if (result != null) bytes -= result.size();
        return result;
    }

    public synchronized void clear() {
        messages.clear();
        bytes = 0;
        recovering = false;
        syncRequested = false;
    }

    public synchronized int size() { return messages.size(); }
    public synchronized int byteSize() { return bytes; }
    public synchronized boolean isRecovering() { return recovering; }
    public synchronized void onTransportDrop() { enterRecovery(); }

    private void enterRecovery() {
        recovering = true;
        removeWhere(message -> !message.config && !message.keyframe);
        if (!syncRequested) {
            syncRequested = true;
            requestSync.run();
        }
    }

    private boolean fits(Message message, long transportBytes) {
        return message.size() <= maxBytes
                && messages.size() < maxMessages
                && transportBytes + bytes + message.size() <= maxBytes;
    }

    private boolean validTransportBytes(long transportBytes) {
        return transportBytes >= 0 && transportBytes <= maxBytes;
    }

    private void removeWhere(java.util.function.Predicate<Message> predicate) {
        Iterator<Message> iterator = messages.iterator();
        while (iterator.hasNext()) {
            Message message = iterator.next();
            if (predicate.test(message)) {
                iterator.remove();
                bytes -= message.size();
            }
        }
    }

    private static Message message(byte[] data, boolean config, boolean keyframe) {
        if (data == null || data.length == 0) throw new IllegalArgumentException("Empty live message");
        return new Message(data, config, keyframe);
    }
}
