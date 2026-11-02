package br.com.redesurftank.havalcamera;

import android.app.Activity;
import android.content.Intent;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
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
    private TripVideoService.LocalBinder service;
    private FrameRouter.Subscription previewSubscription;
    private boolean bound;
    private volatile long lastFrame;
    private boolean active;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = (TripVideoService.LocalBinder) binder; startStream();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null; stopStream();
        }
    };

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
        Intent serviceIntent = new Intent(this, TripVideoService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent); else startService(serviceIntent);
    }

    @Override protected void onResume() {
        super.onResume(); active=true; preview.onResume();
        bound = bindService(new Intent(this, TripVideoService.class), connection, Context.BIND_AUTO_CREATE);
        status.setText("Conectando à câmera…"); handler.post(monitor);
    }

    private void startStream() {
        if (!active) return;
        if (service == null || previewSubscription != null) return;
        previewSubscription = service.subscribePreview(frame -> {
            preview.publish(frame);
            lastFrame = SystemClock.elapsedRealtime();
            runOnUiThread(() -> status.setText("Câmera ao vivo"));
        });
    }

    private void stopStream() {
        lastFrame=0;
        if (previewSubscription != null) previewSubscription.close();
        previewSubscription = null;
        preview.clear();
    }

    @Override protected void onPause() {
        active=false; handler.removeCallbacks(monitor); stopStream();
        if (bound) unbindService(connection); bound=false; service=null;
        preview.onPause(); super.onPause();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
    }
}
