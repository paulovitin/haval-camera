package br.com.redesurftank.havalcamera;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.DataInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;
import rikka.shizuku.Shizuku;

/** Owns one complete-frame stream from the private root capture service. */
public final class CameraCaptureClient implements AutoCloseable {
    public interface FrameConsumer {
        void onFrame(byte[] frame);
    }

    public interface Listener {
        void onState(State state, String detail);
    }

    public enum State {
        NEEDS_SHIZUKU, WAITING_PERMISSION, NEEDS_ROOT, CONNECTING, STREAMING, DISCONNECTED, ERROR
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Shizuku.UserServiceArgs args;
    private volatile DataInputStream input;
    private volatile int generation;
    private ICameraStream service;
    private FrameConsumer consumer;
    private boolean started;
    private boolean binding;
    private boolean permissionPending;
    private boolean closed;

    private final Shizuku.OnBinderReceivedListener binderReceived = this::connect;
    private final Shizuku.OnBinderDeadListener binderDead = () -> {
        stop();
        report(State.DISCONNECTED, null);
    };
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (request, grant) -> {
        synchronized (this) {
            permissionPending = false;
        }
        if (grant == PackageManager.PERMISSION_GRANTED) connect();
        else report(State.ERROR, "Shizuku permission denied");
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            FrameConsumer destination;
            int run;
            synchronized (CameraCaptureClient.this) {
                if (!started || closed) {
                    Shizuku.unbindUserService(args, this, true);
                    binding = false;
                    return;
                }
                service = ICameraStream.Stub.asInterface(binder);
                destination = consumer;
                run = ++generation;
            }
            ICameraStream remote = service;
            new Thread(() -> readFrames(remote, destination, run), "CameraFrames").start();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            synchronized (CameraCaptureClient.this) {
                service = null;
                binding = false;
                generation++;
            }
            report(State.DISCONNECTED, null);
        }
    };

    public CameraCaptureClient(Context context, Listener listener) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        this.listener = Objects.requireNonNull(listener, "listener");
        args = new Shizuku.UserServiceArgs(new ComponentName(this.context, CameraService.class))
                .daemon(false).processNameSuffix("camera").version(2);
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
    }

    public synchronized void start(FrameConsumer frameConsumer) {
        if (closed) throw new IllegalStateException("Capture client is closed");
        consumer = Objects.requireNonNull(frameConsumer, "frameConsumer");
        started = true;
        connect();
    }

    private synchronized void connect() {
        if (!started || closed || binding || permissionPending) return;
        try {
            if (!Shizuku.pingBinder()) {
                report(State.NEEDS_SHIZUKU, null);
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                permissionPending = true;
                report(State.WAITING_PERMISSION, null);
                Shizuku.requestPermission(1);
                return;
            }
            if (Shizuku.getUid() != 0) {
                report(State.NEEDS_ROOT, null);
                return;
            }
            report(State.CONNECTING, null);
            binding = true;
            Shizuku.bindUserService(args, connection);
        } catch (Exception failure) {
            binding = false;
            report(State.ERROR, failure.getMessage());
        }
    }

    private void readFrames(ICameraStream remote, FrameConsumer destination, int run) {
        try {
            File config = new File(context.getFilesDir(), "input4.xml");
            Files.write(config.toPath(), ("<qcarcam_inputs><input_device><properties input_id=\"4\"/>"
                    + "<display_setting display_id=\"0\"/><output_setting nbufs=\"5\"/>"
                    + "</input_device></qcarcam_inputs>").getBytes(StandardCharsets.UTF_8));
            ParcelFileDescriptor descriptor = remote.start(
                    context.getApplicationInfo().nativeLibraryDir + "/libcamera_stream.so",
                    config.getPath());
            try (DataInputStream stream = new DataInputStream(
                    new ParcelFileDescriptor.AutoCloseInputStream(descriptor))) {
                if (run != generation) {
                    remote.stop();
                    return;
                }
                input = stream;
                byte[] frame = new byte[CameraPreview.FRAME_SIZE];
                long count = 0;
                while (run == generation) {
                    stream.readFully(frame);
                    if (run != generation) break;
                    destination.onFrame(frame);
                    if (++count == 1) report(State.STREAMING, null);
                    if (count == 1 || count % 150 == 0) {
                        Log.i("HavalCamera", "Live frames=" + count);
                    }
                }
            }
        } catch (Exception failure) {
            Log.e("HavalCamera", "Stream ended", failure);
            if (run == generation) report(State.ERROR, failure.getMessage());
        } finally {
            if (run == generation) input = null;
        }
    }

    public synchronized void stop() {
        started = false;
        consumer = null;
        generation++;
        try {
            if (input != null) input.close();
        } catch (Exception ignored) { }
        input = null;
        try {
            if (service != null) service.stop();
        } catch (Exception failure) {
            Log.w("HavalCamera", "Stop", failure);
        }
        service = null;
        if (binding && Shizuku.pingBinder()) {
            try {
                Shizuku.unbindUserService(args, connection, true);
            } catch (Exception failure) {
                Log.w("HavalCamera", "Unbind", failure);
            }
        }
        binding = false;
    }

    private void report(State state, String detail) {
        main.post(() -> listener.onState(state, detail));
    }

    @Override public synchronized void close() {
        if (closed) return;
        stop();
        closed = true;
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permissionResult);
    }
}
