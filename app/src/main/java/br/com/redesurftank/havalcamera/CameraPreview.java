package br.com.redesurftank.havalcamera;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

final class CameraPreview extends GLSurfaceView implements GLSurfaceView.Renderer {
    static final int WIDTH = 1760, HEIGHT = 720, FRAME_SIZE = WIDTH * HEIGHT * 3;
    private final ByteBuffer pixels = ByteBuffer.allocateDirect(FRAME_SIZE);
    private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final TelemetryOverlayRenderer overlay = new TelemetryOverlayRenderer();
    private boolean available, pending;
    private int program, texture, position, uv, screenWidth, screenHeight;

    CameraPreview(Context context) {
        super(context);
        quad.put(new float[]{-1,-1,0,1, 1,-1,1,1, -1,1,0,0, 1,1,1,0}).position(0);
        setEGLContextClientVersion(2);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    synchronized void publish(byte[] bytes) {
        if (bytes.length != FRAME_SIZE) throw new IllegalArgumentException("Invalid frame size");
        pixels.position(0); pixels.put(bytes); pixels.position(0);
        available = pending = true;
        requestRender();
    }

    synchronized void clear() { available = false; requestRender(); }

    void setOverlaySnapshot(TelemetryOverlayModel.Snapshot snapshot) {
        overlay.setSnapshot(snapshot);
        requestRender();
    }

    private int shader(int kind, String source) {
        int s = GLES20.glCreateShader(kind);
        GLES20.glShaderSource(s, source); GLES20.glCompileShader(s);
        int[] ok = new int[1]; GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(s));
        return s;
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        int vertex = shader(GLES20.GL_VERTEX_SHADER,
            "attribute vec2 p; attribute vec2 t; varying vec2 uv; void main(){gl_Position=vec4(p,0.,1.);uv=t;}");
        int fragment = shader(GLES20.GL_FRAGMENT_SHADER,
            "precision mediump float; uniform sampler2D image; varying vec2 uv; void main(){gl_FragColor=vec4(texture2D(image,uv).bgr,1.);}");
        program = GLES20.glCreateProgram(); GLES20.glAttachShader(program,vertex); GLES20.glAttachShader(program,fragment);
        GLES20.glLinkProgram(program); GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment);
        int[] ok = new int[1]; GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
        position = GLES20.glGetAttribLocation(program,"p"); uv = GLES20.glGetAttribLocation(program,"t");
        int[] textures = new int[1]; GLES20.glGenTextures(1,textures,0); texture=textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT,1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGB,WIDTH,HEIGHT,0,GLES20.GL_RGB,GLES20.GL_UNSIGNED_BYTE,null);
        overlay.onSurfaceCreated();
        synchronized (this) { pending = available; }
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int h) { screenWidth=w; screenHeight=h; }

    @Override public synchronized void onDrawFrame(GL10 gl) {
        GLES20.glClearColor(0,0,0,1); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (!available) return;
        float scale=Math.min(screenWidth/(float)WIDTH,screenHeight/(float)HEIGHT);
        int w=Math.round(WIDTH*scale), h=Math.round(HEIGHT*scale);
        GLES20.glViewport((screenWidth-w)/2,(screenHeight-h)/2,w,h);
        GLES20.glUseProgram(program); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
        if (pending) {
            pixels.position(0);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,WIDTH,HEIGHT,GLES20.GL_RGB,GLES20.GL_UNSIGNED_BYTE,pixels);
            pending=false;
        }
        quad.position(0); GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,16,quad);
        quad.position(2); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,quad);
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(uv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        GLES20.glViewport(0,0,screenWidth,screenHeight);
        overlay.draw(screenWidth,screenHeight);
    }
}
