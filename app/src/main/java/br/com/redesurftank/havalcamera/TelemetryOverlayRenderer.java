package br.com.redesurftank.havalcamera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Reuses one bitmap and one GL quad; snapshot changes, not video frames, rebuild its text. */
final class TelemetryOverlayRenderer {
    private static final int TEXTURE_WIDTH = 512;
    private static final int TEXTURE_HEIGHT = 256;
    private static final int MARGIN = 24;
    private static final int DISPLAY_WIDTH = 384;
    private static final int DISPLAY_HEIGHT = 192;

    private final Bitmap bitmap = Bitmap.createBitmap(
            TEXTURE_WIDTH, TEXTURE_HEIGHT, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(bitmap);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat dateTime = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US);
    private final DecimalFormat oneDecimal = new DecimalFormat("0.0");
    private final float[] vertices = new float[16];
    private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private TelemetryOverlayModel.Position position = TelemetryOverlayModel.Position.BOTTOM_LEFT;
    private boolean visible;
    private boolean dirty;
    private int program;
    private int texture;
    private int positionAttribute;
    private int uvAttribute;

    TelemetryOverlayRenderer() {
        paint.setColor(Color.WHITE);
        paint.setTextSize(34f);
        paint.setShadowLayer(4f, 1f, 1f, Color.BLACK);
    }

    synchronized void setSnapshot(TelemetryOverlayModel.Snapshot snapshot) {
        bitmap.eraseColor(Color.TRANSPARENT);
        position = snapshot.position;
        float y = 38f;
        if (snapshot.wallClockMs != null) {
            canvas.drawText(dateTime.format(new Date(snapshot.wallClockMs)), 8f, y, paint);
            y += 40f;
        }
        if (snapshot.speedKmh != null) {
            canvas.drawText(oneDecimal.format(snapshot.speedKmh) + " km/h", 8f, y, paint);
            y += 40f;
        }
        if (snapshot.accelerationMps2 != null) {
            canvas.drawText(oneDecimal.format(snapshot.accelerationMps2) + " m/s²", 8f, y, paint);
            y += 40f;
        }
        if (!snapshot.driveLabel.isEmpty()) {
            canvas.drawText(snapshot.driveLabel, 8f, y, paint);
            y += 40f;
        }
        if (!snapshot.decelerationLabel.isEmpty()) {
            canvas.drawText(snapshot.decelerationLabel, 8f, y, paint);
            y += 40f;
        }
        visible = y > 38f;
        dirty = true;
    }

    void onSurfaceCreated() {
        int vertex = shader(GLES20.GL_VERTEX_SHADER,
                "attribute vec2 p; attribute vec2 t; varying vec2 uv;"
                        + "void main(){gl_Position=vec4(p,0.,1.);uv=t;}");
        int fragment = shader(GLES20.GL_FRAGMENT_SHADER,
                "precision mediump float; uniform sampler2D image; varying vec2 uv;"
                        + "void main(){gl_FragColor=texture2D(image,uv);}");
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
        positionAttribute = GLES20.glGetAttribLocation(program, "p");
        uvAttribute = GLES20.glGetAttribLocation(program, "t");
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        texture = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        dirty = false;
    }

    synchronized void draw(int surfaceWidth, int surfaceHeight) {
        if (!visible || surfaceWidth <= 0 || surfaceHeight <= 0) return;
        GLES20.glUseProgram(program);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        if (dirty) {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
            dirty = false;
        }

        float left = position == TelemetryOverlayModel.Position.TOP_LEFT
                || position == TelemetryOverlayModel.Position.BOTTOM_LEFT
                ? MARGIN : surfaceWidth - MARGIN - DISPLAY_WIDTH;
        float top = position == TelemetryOverlayModel.Position.TOP_LEFT
                || position == TelemetryOverlayModel.Position.TOP_RIGHT
                ? MARGIN : surfaceHeight - MARGIN - DISPLAY_HEIGHT;
        float x0 = left / surfaceWidth * 2f - 1f;
        float x1 = (left + DISPLAY_WIDTH) / surfaceWidth * 2f - 1f;
        float y0 = 1f - (top + DISPLAY_HEIGHT) / surfaceHeight * 2f;
        float y1 = 1f - top / surfaceHeight * 2f;
        putVertex(0, x0, y0, 0f, 1f);
        putVertex(1, x1, y0, 0.75f, 1f);
        putVertex(2, x0, y1, 0f, 0.25f);
        putVertex(3, x1, y1, 0.75f, 0.25f);
        quad.position(0);
        quad.put(vertices).position(0);
        GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 16, quad);
        quad.position(2);
        GLES20.glVertexAttribPointer(uvAttribute, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(positionAttribute);
        GLES20.glEnableVertexAttribArray(uvAttribute);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void putVertex(int index, float x, float y, float u, float v) {
        int offset = index * 4;
        vertices[offset] = x;
        vertices[offset + 1] = y;
        vertices[offset + 2] = u;
        vertices[offset + 3] = v;
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
}
