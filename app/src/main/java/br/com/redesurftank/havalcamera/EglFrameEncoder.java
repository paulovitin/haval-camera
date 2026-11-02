package br.com.redesurftank.havalcamera;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.Objects;

/** Uploads each BGR frame once and feeds a hardware AVC surface encoder. */
public final class EglFrameEncoder implements AutoCloseable, RecordingCoordinator.EncoderControl {
    public static final String MIME = "video/avc";
    public static final int WIDTH = CameraPreview.WIDTH;
    public static final int HEIGHT = CameraPreview.HEIGHT;
    public static final int FRAME_RATE = 15;
    public static final int BIT_RATE = 4_000_000;

    public interface Listener {
        void onOutputFormat(MediaFormat format);
        void onCodecConfig(byte[] sps, byte[] pps);
        void onAccessUnit(EncodedAccessUnit unit);
        void onFailure(Throwable failure);
    }

    private final Listener listener;
    private final ByteBuffer pendingPixels = ByteBuffer.allocateDirect(CameraPreview.FRAME_SIZE);
    private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final TelemetryOverlayRenderer overlay = new TelemetryOverlayRenderer();
    private final EncoderLifecycle lifecycle = new EncoderLifecycle();
    private MediaCodec codec;
    private Surface inputSurface;
    private Thread renderThread;
    private Thread drainThread;
    private boolean framePending;
    private long pendingPtsUs;
    private volatile boolean failureReported;

    public EglFrameEncoder(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        quad.put(new float[]{-1,-1,0,1, 1,-1,1,1, -1,1,0,0, 1,1,1,0}).position(0);
    }

    public synchronized String start() throws Exception {
        if (codec != null) throw new IllegalStateException("Encoder already started");
        Exception lastFailure = null;
        for (MediaCodecInfo info : hardwareSurfaceEncoders()) {
            MediaCodec candidate = null;
            try {
                candidate = MediaCodec.createByCodecName(info.getName());
                MediaFormat format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                if (Build.VERSION.SDK_INT >= 30 && info.getCapabilitiesForType(MIME)
                        .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                }
                candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                inputSurface = candidate.createInputSurface();
                candidate.start();
                codec = candidate;
                lifecycle.start();
                failureReported = false;
                renderThread = new Thread(this::renderLoop, "CameraEglEncoder");
                drainThread = new Thread(this::drainLoop, "CameraCodecDrain");
                renderThread.start();
                drainThread.start();
                return info.getName();
            } catch (Exception failure) {
                lastFailure = failure;
                if (candidate != null) {
                    try { candidate.release(); } catch (Exception ignored) { }
                }
                inputSurface = null;
            }
        }
        throw new IllegalStateException("No hardware AVC surface encoder available", lastFailure);
    }

    public synchronized void queueFrame(byte[] bgr24, long presentationTimeUs) {
        if (bgr24 == null || bgr24.length != CameraPreview.FRAME_SIZE) {
            throw new IllegalArgumentException("Invalid BGR24 frame");
        }
        lifecycle.acceptPresentationTime(presentationTimeUs);
        pendingPixels.position(0);
        pendingPixels.put(bgr24);
        pendingPixels.position(0);
        pendingPtsUs = presentationTimeUs;
        framePending = true;
        notifyAll();
    }

    public void setOverlaySnapshot(TelemetryOverlayModel.Snapshot snapshot) {
        overlay.setSnapshot(Objects.requireNonNull(snapshot, "snapshot"));
    }

    @Override public void requestKeyFrame() {
        MediaCodec current = codec;
        if (!lifecycle.acceptingFrames() || current == null) return;
        Bundle parameters = new Bundle();
        parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        current.setParameters(parameters);
    }

    private void renderLoop() {
        EglState egl = null;
        try {
            egl = new EglState(inputSurface);
            GlState gl = new GlState();
            overlay.onSurfaceCreated();
            while (true) {
                long ptsUs;
                synchronized (this) {
                    while (lifecycle.acceptingFrames() && !framePending) wait();
                    if (!framePending) break;
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gl.texture);
                    pendingPixels.position(0);
                    GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
                            WIDTH, HEIGHT, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, pendingPixels);
                    ptsUs = pendingPtsUs;
                    framePending = false;
                }
                GLES20.glViewport(0, 0, WIDTH, HEIGHT);
                GLES20.glUseProgram(gl.program);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gl.texture);
                quad.position(0);
                GLES20.glVertexAttribPointer(gl.position, 2, GLES20.GL_FLOAT, false, 16, quad);
                quad.position(2);
                GLES20.glVertexAttribPointer(gl.uv, 2, GLES20.GL_FLOAT, false, 16, quad);
                GLES20.glEnableVertexAttribArray(gl.position);
                GLES20.glEnableVertexAttribArray(gl.uv);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                overlay.draw(WIDTH, HEIGHT);
                EGLExt.eglPresentationTimeANDROID(egl.display, egl.surface, ptsUs * 1_000L);
                if (!EGL14.eglSwapBuffers(egl.display, egl.surface)) {
                    throw new IllegalStateException("EGL encoder swap failed");
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable failure) {
            fail(failure);
        } finally {
            if (egl != null) egl.close();
        }
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        MediaCodec drainingCodec = codec;
        try {
            while (lifecycle.drainingOutput()) {
                int index = drainingCodec.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = drainingCodec.getOutputFormat();
                    listener.onOutputFormat(format);
                    ByteBuffer csd0 = format.getByteBuffer("csd-0");
                    ByteBuffer csd1 = format.getByteBuffer("csd-1");
                    if (csd0 != null && csd1 != null) {
                        listener.onCodecConfig(copy(csd0), copy(csd1));
                    }
                    continue;
                }
                if (index < 0) continue;
                ByteBuffer output = drainingCodec.getOutputBuffer(index);
                if (output != null && info.size > 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    output.position(info.offset);
                    output.limit(info.offset + info.size);
                    byte[] bytes = new byte[info.size];
                    output.get(bytes);
                    listener.onAccessUnit(new EncodedAccessUnit(
                            bytes,
                            info.presentationTimeUs,
                            (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
                            (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0));
                }
                boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                drainingCodec.releaseOutputBuffer(index, false);
                if (end) {
                    lifecycle.endOfStream();
                    break;
                }
            }
        } catch (Throwable failure) {
            if (lifecycle.drainingOutput()) fail(failure);
        }
    }

    private static byte[] copy(ByteBuffer source) {
        ByteBuffer data = source.duplicate();
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        return bytes;
    }

    private void fail(Throwable failure) {
        if (failureReported) return;
        failureReported = true;
        lifecycle.fail();
        synchronized (this) { notifyAll(); }
        listener.onFailure(failure);
    }

    @Override public void close() {
        MediaCodec current;
        Surface surface;
        Thread renderer;
        Thread drainer;
        synchronized (this) {
            if (codec == null) return;
            lifecycle.beginDrain();
            notifyAll();
            current = codec;
            surface = inputSurface;
            renderer = renderThread;
            drainer = drainThread;
            codec = null;
            inputSurface = null;
        }
        join(renderer);
        if (current != null) {
            try {
                current.signalEndOfInputStream();
            } catch (Exception failure) {
                fail(failure);
            }
        }
        join(drainer);
        if (current != null) {
            try { current.stop(); } catch (Exception ignored) { }
            current.release();
        }
        if (surface != null) surface.release();
    }

    private static void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) return;
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    static final class EncoderLifecycle {
        private boolean acceptingFrames;
        private boolean drainingOutput;
        private boolean hasPresentationTime;
        private long lastPresentationTimeUs;

        synchronized void start() {
            acceptingFrames = true;
            drainingOutput = true;
            hasPresentationTime = false;
        }

        synchronized void acceptPresentationTime(long presentationTimeUs) {
            if (!acceptingFrames) throw new IllegalStateException("Encoder is not accepting frames");
            if (presentationTimeUs < 0) {
                throw new IllegalArgumentException("Negative presentation time");
            }
            if (hasPresentationTime && presentationTimeUs <= lastPresentationTimeUs) {
                throw new IllegalArgumentException("Presentation times must strictly increase");
            }
            lastPresentationTimeUs = presentationTimeUs;
            hasPresentationTime = true;
        }

        synchronized void beginDrain() {
            acceptingFrames = false;
        }

        synchronized void endOfStream() {
            acceptingFrames = false;
            drainingOutput = false;
        }

        synchronized void fail() {
            acceptingFrames = false;
            drainingOutput = false;
        }

        synchronized boolean acceptingFrames() {
            return acceptingFrames;
        }

        synchronized boolean drainingOutput() {
            return drainingOutput;
        }
    }

    private static MediaCodecInfo[] hardwareSurfaceEncoders() {
        return java.util.Arrays.stream(new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos())
                .filter(MediaCodecInfo::isEncoder)
                .filter(EglFrameEncoder::supportsAvcSurface)
                .filter(EglFrameEncoder::isHardware)
                .toArray(MediaCodecInfo[]::new);
    }

    private static boolean supportsAvcSurface(MediaCodecInfo info) {
        for (String type : info.getSupportedTypes()) {
            if (!MIME.equalsIgnoreCase(type)) continue;
            for (int color : info.getCapabilitiesForType(type).colorFormats) {
                if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) return true;
            }
        }
        return false;
    }

    private static boolean isHardware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated();
        String name = info.getName().toLowerCase(Locale.US);
        return name.startsWith("omx.qcom.") || name.startsWith("c2.qti.");
    }

    private static int shader(int kind, String source) {
        int shader = GLES20.glCreateShader(kind);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(shader));
        return shader;
    }

    private static final class GlState {
        final int program;
        final int texture;
        final int position;
        final int uv;

        GlState() {
            int vertex = shader(GLES20.GL_VERTEX_SHADER,
                    "attribute vec2 p; attribute vec2 t; varying vec2 uv;"
                            + "void main(){gl_Position=vec4(p,0.,1.);uv=t;}");
            int fragment = shader(GLES20.GL_FRAGMENT_SHADER,
                    "precision mediump float; uniform sampler2D image; varying vec2 uv;"
                            + "void main(){gl_FragColor=vec4(texture2D(image,uv).bgr,1.);}");
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
            position = GLES20.glGetAttribLocation(program, "p");
            uv = GLES20.glGetAttribLocation(program, "t");
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            texture = textures[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, WIDTH, HEIGHT, 0,
                    GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, null);
        }
    }

    private static final class EglState implements AutoCloseable {
        final EGLDisplay display;
        final EGLContext context;
        final EGLSurface surface;

        EglState(Surface encoderSurface) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw new IllegalStateException("Unable to initialize EGL");
            }
            int[] attributes = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    0x3142, 1,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)) {
                throw new IllegalStateException("Unable to choose recordable EGL config");
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface,
                    new int[]{EGL14.EGL_NONE}, 0);
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw new IllegalStateException("Unable to make encoder EGL context current");
            }
        }

        @Override public void close() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }
}
