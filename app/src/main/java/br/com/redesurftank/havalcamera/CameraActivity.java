package br.com.redesurftank.havalcamera;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class CameraActivity extends Activity {
    private final Handler handler = new Handler();
    private CameraPreview preview;
    private TextView status;
    private CameraCaptureClient capture;
    private volatile long lastFrame;
    private boolean active;

    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (lastFrame != 0 && SystemClock.elapsedRealtime()-lastFrame > 2500) {
                preview.clear(); status.setText("Sem imagem da câmera. Toque em Conectar para tentar novamente.");
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.BLACK);
        preview = new CameraPreview(this);
        layout.addView(preview, new FrameLayout.LayoutParams(-1,-1));
        status = new TextView(this);
        status.setTextColor(Color.WHITE); status.setTextSize(18);
        status.setPadding(20,12,20,12); status.setBackgroundColor(0x99000000);
        layout.addView(status,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT));
        LinearLayout actions = new LinearLayout(this);
        Button retry = new Button(this); retry.setText("Conectar");
        retry.setOnClickListener(v -> { stopStream(); startStream(); });
        Button close = new Button(this); close.setText("Fechar"); close.setOnClickListener(v -> finish());
        Button gallery = new Button(this); gallery.setText("Gravações");
        gallery.setOnClickListener(v -> startActivity(new Intent(this, VideoGalleryActivity.class)));
        Button settings = new Button(this); settings.setText("Config");
        settings.setOnClickListener(v -> startActivity(new Intent(this, VideoSettingsActivity.class)));
        actions.addView(retry); actions.addView(gallery); actions.addView(settings); actions.addView(close);
        layout.addView(actions,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT));
        setContentView(layout);
        capture = new CameraCaptureClient(this, this::onCaptureState);
    }

    @Override protected void onResume() {
        super.onResume(); active=true; preview.onResume(); startStream(); handler.post(monitor);
    }

    private void startStream() {
        if (!active) return;
        capture.start(frame -> {
            preview.publish(frame);
            lastFrame = SystemClock.elapsedRealtime();
        });
    }

    private void onCaptureState(CameraCaptureClient.State state, String detail) {
        if (!active) return;
        switch (state) {
            case NEEDS_SHIZUKU:
                status.setText("Inicie o Shizuku e toque em Conectar.");
                break;
            case WAITING_PERMISSION:
                status.setText("Aguardando autorização do Shizuku…");
                break;
            case NEEDS_ROOT:
                status.setText("Nesta central, inicie o Shizuku com acesso root.");
                break;
            case CONNECTING:
                status.setText("Conectando à câmera…");
                break;
            case STREAMING:
                status.setText("Câmera ao vivo");
                break;
            case DISCONNECTED:
                preview.clear();
                status.setText("Captura encerrada. Toque em Conectar.");
                break;
            case ERROR:
                preview.clear();
                status.setText("Sem imagem: " + (detail == null ? "captura encerrada" : detail));
                break;
        }
    }

    private void stopStream() {
        lastFrame=0;
        capture.stop();
        preview.clear();
    }

    @Override protected void onPause() {
        active=false; handler.removeCallbacks(monitor); stopStream(); preview.onPause(); super.onPause();
    }

    @Override protected void onDestroy() {
        capture.close();
        super.onDestroy();
    }
}
