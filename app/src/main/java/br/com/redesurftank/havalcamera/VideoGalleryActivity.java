package br.com.redesurftank.havalcamera;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Standalone local gallery, manual controls, and user-started LAN sharing. */
public final class VideoGalleryActivity extends Activity {
    private LinearLayout list;
    private VideoCatalog catalog;
    private LocalShareServer sharing;
    private TextView shareStatus;
    private final Handler handler = new Handler();
    private final Runnable countdown = new Runnable() {
        @Override public void run() {
            if (sharing == null) return;
            long seconds = Math.max(0, (sharing.expiresAtMs() - System.currentTimeMillis()) / 1_000);
            shareStatus.setText(shareStatus.getTag() + "  Restam " + seconds + " s");
            if (seconds == 0) stopSharing(); else handler.postDelayed(this, 1_000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try { catalog = VideoCatalog.shared(getFilesDir()); }
        catch (Exception failure) { finish(); return; }
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(24,24,24,24);
        Button start = button("Iniciar gravação manual", v -> command(VideoContract.ACTION_START_MANUAL_RECORDING));
        Button stop = button("Parar gravação manual", v -> command(VideoContract.ACTION_STOP_MANUAL_RECORDING));
        Button share = button("Compartilhar na rede local", v -> startSharing());
        Button delete = button("Apagar gravações concluídas", v -> confirmDelete());
        shareStatus = new TextView(this); shareStatus.setTextColor(Color.WHITE);
        list.addView(start); list.addView(stop); list.addView(share); list.addView(delete); list.addView(shareStatus);
        setContentView(list); render();
    }
    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(listener); return button;
    }
    private void command(String action) {
        long now = System.currentTimeMillis();
        Intent intent = new Intent(this, TripVideoService.class).setAction(action)
                .putExtra(VideoContract.EXTRA_START_MS, now).putExtra(VideoContract.EXTRA_END_MS, now);
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        render();
    }
    private void render() {
        while (list.getChildCount() > 5) list.removeViewAt(5);
        for (VideoCatalog.Trip trip : catalog.localSnapshot()) {
            Button row = button((trip.completed ? "Concluída " : "Em gravação ") + trip.tripId
                    + " · " + trip.segments.size() + " segmentos", v -> {
                if (!trip.completed || trip.segments.isEmpty()) return;
                startActivity(new Intent(this, VideoPlaybackActivity.class).putExtra("tripId", Long.toString(trip.tripId)));
            });
            list.addView(row);
        }
    }
    private void startSharing() {
        try {
            if (sharing != null) sharing.close();
            InetAddress address = privateWifiAddress();
            sharing = new LocalShareServer(address, getFilesDir(), catalog);
            shareStatus.setTag("http://" + address.getHostAddress() + ":" + sharing.port()
                    + "  Código: " + sharing.pairingCode());
            handler.removeCallbacks(countdown); handler.post(countdown);
            Button stop = button("Parar compartilhamento", v -> stopSharing()); list.addView(stop);
        } catch (Exception failure) { shareStatus.setText("Rede privada indisponível: " + failure.getMessage()); }
    }
    private InetAddress privateWifiAddress() throws Exception {
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback()) continue;
            for (InetAddress address : Collections.list(network.getInetAddresses()))
                if (LocalShareServer.isPrivateWifiAddress(address.getHostAddress())) return address;
        }
        throw new IllegalStateException("Wi-Fi privada não encontrada");
    }
    private void stopSharing() { handler.removeCallbacks(countdown); if (sharing != null) sharing.close(); sharing = null; shareStatus.setText("Compartilhamento encerrado"); }
    private void confirmDelete() {
        new AlertDialog.Builder(this).setTitle("Apagar gravações concluídas?")
                .setMessage("Gravações ativas não serão apagadas.").setNegativeButton("Cancelar", null)
                .setPositiveButton("Apagar", (dialog, which) -> {
                    startService(new Intent(this, LocalVideoActionService.class));
                }).show();
    }
    @Override protected void onDestroy() { stopSharing(); super.onDestroy(); }
}
