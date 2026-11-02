package br.com.redesurftank.havalcamera;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.DataInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import rikka.shizuku.Shizuku;

public final class CameraActivity extends Activity {
    private final Handler handler = new Handler();
    private CameraPreview preview;
    private TextView status;
    private ICameraStream service;
    private Shizuku.UserServiceArgs args;
    private volatile DataInputStream input;
    private volatile int generation;
    private volatile long lastFrame;
    private boolean active, binding, permissionPending;
    private final Shizuku.OnBinderReceivedListener binderReceived = this::connect;
    private final Shizuku.OnBinderDeadListener binderDead = () -> {
        stopStream(); status.setText("Shizuku desconectado. Abra-o e toque em Conectar.");
    };
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (request, grant) -> {
        permissionPending = false;
        if (grant == PackageManager.PERMISSION_GRANTED) connect();
        else status.setText("Autorize o acesso pelo Shizuku para abrir a câmera.");
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

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!active) { Shizuku.unbindUserService(args, this, true); return; }
            service = ICameraStream.Stub.asInterface(binder);
            int run = ++generation;
            ICameraStream remote = service;
            new Thread(() -> readFrames(remote, run), "CameraFrames").start();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null; binding = false; generation++;
            preview.clear(); status.setText("Captura encerrada. Toque em Conectar.");
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
        retry.setOnClickListener(v -> { stopStream(); connect(); });
        Button close = new Button(this); close.setText("Fechar"); close.setOnClickListener(v -> finish());
        actions.addView(retry); actions.addView(close);
        layout.addView(actions,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT));
        setContentView(layout);
        args = new Shizuku.UserServiceArgs(new ComponentName(this,CameraService.class))
                .daemon(false).processNameSuffix("camera").version(2);
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
    }

    @Override protected void onResume() {
        super.onResume(); active=true; preview.onResume(); connect(); handler.post(monitor);
    }

    private void connect() {
        if (!active || binding || permissionPending) return;
        try {
            if (!Shizuku.pingBinder()) { status.setText("Inicie o Shizuku e toque em Conectar."); return; }
            if (Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) {
                permissionPending=true; status.setText("Aguardando autorização do Shizuku…");
                Shizuku.requestPermission(1); return;
            }
            if (Shizuku.getUid()!=0) { status.setText("Nesta central, inicie o Shizuku com acesso root."); return; }
            status.setText("Conectando à câmera…");
            binding=true; Shizuku.bindUserService(args,connection);
        } catch (Exception e) { binding=false; status.setText("Não foi possível conectar: "+e.getMessage()); }
    }

    private void readFrames(ICameraStream remote, int run) {
        try {
            File config = new File(getFilesDir(),"input4.xml");
            Files.write(config.toPath(), ("<qcarcam_inputs><input_device><properties input_id=\"4\"/>"
                + "<display_setting display_id=\"0\"/><output_setting nbufs=\"5\"/>"
                + "</input_device></qcarcam_inputs>").getBytes(StandardCharsets.UTF_8));
            ParcelFileDescriptor fd = remote.start(getApplicationInfo().nativeLibraryDir+"/libcamera_stream.so",config.getPath());
            try (DataInputStream stream = new DataInputStream(new ParcelFileDescriptor.AutoCloseInputStream(fd))) {
                if (run!=generation) { remote.stop(); return; }
                input=stream;
                byte[] frame=new byte[CameraPreview.FRAME_SIZE];
                long count=0;
                while (run==generation) {
                    stream.readFully(frame);
                    if (run!=generation) break;
                    preview.publish(frame); lastFrame=SystemClock.elapsedRealtime();
                    if (++count==1 || count%150==0) {
                        Log.i("HavalCamera","Live frames="+count);
                        handler.post(() -> { if (run==generation) status.setText("Câmera ao vivo"); });
                    }
                }
            }
        } catch (Exception e) {
            Log.e("HavalCamera","Stream ended",e);
            handler.post(() -> { if (run==generation) {
                preview.clear(); status.setText("Sem imagem: "+(e.getMessage()==null ? "captura encerrada" : e.getMessage()));
            }});
        }
    }

    private void stopStream() {
        generation++; lastFrame=0;
        try { if (service!=null) service.stop(); } catch (Exception e) { Log.w("HavalCamera","Stop",e); }
        try { if (input!=null) input.close(); } catch (Exception ignored) { }
        input=null; service=null;
        if (binding && Shizuku.pingBinder()) {
            try { Shizuku.unbindUserService(args,connection,true); } catch (Exception e) { Log.w("HavalCamera","Unbind",e); }
        }
        binding=false; preview.clear();
    }

    @Override protected void onPause() {
        active=false; handler.removeCallbacks(monitor); stopStream(); preview.onPause(); super.onPause();
    }

    @Override protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived); Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permissionResult); super.onDestroy();
    }
}
