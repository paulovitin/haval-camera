package br.com.redesurftank.havalcamera;

import android.os.Bundle;
import android.os.IBinder;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.ClipData;
import android.content.pm.PackageInfo;
import android.provider.Settings;
import java.io.File;
import okhttp3.OkHttpClient;
import android.net.Uri;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Map;

/** Local source-of-truth settings screen. */
public final class VideoSettingsActivity extends BackNavigationActivity {
    private VideoSettings settings;
    private final Map<String, CheckBox> toggles = new HashMap<>();
    private Spinner quality, reserve, position;
    private CheckBox prerelease;
    private Button checkUpdate;
    private TextView updateStatus;
    private AppUpdater updater;
    private TripVideoService.LocalBinder videoService;
    private boolean updateBound, updateActive;
    private File pendingApk;
    private AlertDialog updateDialog;
    private String currentVersion;
    private final ServiceConnection updateConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            videoService = (TripVideoService.LocalBinder) binder;
            checkUpdate.setEnabled(updateActive && updater != null);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            videoService = null; checkUpdate.setEnabled(false);
            updateStatus.setText("Serviço da câmera desconectado; atualização bloqueada.");
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); settings = VideoSettings.from(this);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(24,24,24,24);
        addToggle(content, "automaticRecording", "Gravar viagens automaticamente");
        addToggle(content, "recordParkedLive", "Gravar transmissão ao vivo estacionado");
        addToggle(content, "remoteLive", "Permitir visualização remota");
        addToggle(content, "remoteDownloads", "Permitir downloads remotos");
        addToggle(content, "automaticDeletion", "Excluir gravações antigas automaticamente");
        addToggle(content, "overlayMaster", "Carimbo de telemetria");
        addToggle(content, "overlayDateTime", "Carimbo: data e hora");
        addToggle(content, "overlaySpeed", "Carimbo: velocidade");
        addToggle(content, "overlayAcceleration", "Carimbo: aceleração");
        addToggle(content, "overlayDriveState", "Carimbo: estado de condução");
        addToggle(content, "overlayDecelerationRegen", "Carimbo: desaceleração/regeneração");
        quality = spinner(content, "Qualidade", new String[]{"economy","standard","high"});
        reserve = spinner(content, "Reserva livre (GB)", new String[]{"5","10","20"});
        position = spinner(content, "Posição do carimbo", new String[]{"top_left","top_right","bottom_left","bottom_right"});
        Button save = new Button(this); save.setText("Salvar"); save.setOnClickListener(v -> save()); content.addView(save);
        prerelease = new CheckBox(this); prerelease.setText("Receber versões de teste");
        prerelease.setChecked(getSharedPreferences("updates", MODE_PRIVATE).getBoolean("prerelease", false));
        prerelease.setOnCheckedChangeListener((button, checked) ->
                getSharedPreferences("updates", MODE_PRIVATE).edit().putBoolean("prerelease", checked).apply());
        content.addView(prerelease);
        checkUpdate = new Button(this); checkUpdate.setText("Verificar atualização"); checkUpdate.setEnabled(false);
        checkUpdate.setOnClickListener(v -> checkForUpdate()); content.addView(checkUpdate);
        updateStatus = new TextView(this);
        updateStatus.setText("A atualização reinicia o app. A visualização local é encerrada ao abrir Configurações; aguarde a gravação ficar ociosa.");
        content.addView(updateStatus);
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        setContentView(scroll); load();
    }
    @Override protected void onResume() {
        super.onResume(); updateActive = true; prerelease.setEnabled(true);
        try {
            PackageInfo installed = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = installed.getLongVersionCode();
            if (code > Integer.MAX_VALUE) throw new IllegalStateException("Versão instalada fora do esquema OTA");
            currentVersion = installed.versionName + " (" + code + ")";
            updater = new AppUpdater(new OkHttpClient(), getCacheDir(), (int) code,
                    apk -> UpdateVerifier.inspectArchive(apk, getPackageManager()));
            updateBound = bindService(new Intent(this, TripVideoService.class), updateConnection, Context.BIND_AUTO_CREATE);
            if (!updateBound) updateStatus.setText("Não foi possível confirmar o estado da câmera.");
        } catch (Exception error) { updateStatus.setText("Atualização indisponível: " + error.getMessage()); }
    }
    @Override protected void onPause() {
        updateActive = false;
        if (updateDialog != null) updateDialog.dismiss(); updateDialog = null;
        if (updater != null) updater.close(); updater = null;
        if (updateBound) unbindService(updateConnection); updateBound = false; videoService = null;
        discardPendingApk(); checkUpdate.setEnabled(false);
        super.onPause();
    }
    private boolean updateIdle() {
        boolean idle = videoService != null && videoService.isUpdateIdle();
        if (!idle) updateStatus.setText("Atualização bloqueada: encerre a visualização e aguarde captura e gravação ficarem ociosas. Nenhuma gravação será interrompida.");
        return idle;
    }
    private void discardPendingApk() {
        if (pendingApk != null) pendingApk.delete(); pendingApk = null;
    }
    private void updateFailed(String reason) {
        if (!updateActive) return;
        checkUpdate.setEnabled(videoService != null); prerelease.setEnabled(true); updateStatus.setText(reason);
    }
    private void checkForUpdate() {
        if (updater == null || !updateIdle()) return;
        discardPendingApk(); checkUpdate.setEnabled(false); prerelease.setEnabled(false);
        updateStatus.setText("Consultando releases do GitHub…");
        AppUpdater request = updater;
        request.check(prerelease.isChecked(), new AppUpdater.Callback() {
            @Override public void failed(String reason) { runOnUiThread(() -> { if (updater == request) updateFailed(reason); }); }
            @Override public void checked(AppUpdater.Offer offer) { runOnUiThread(() -> {
                if (!updateActive || updater != request) return;
                if (offer == null) { updateFailed("Nenhuma atualização mais nova disponível."); return; }
                updateStatus.setText("Atualização disponível: " + offer.manifest.versionName);
                updateDialog = new AlertDialog.Builder(VideoSettingsActivity.this).setTitle("Baixar atualização?")
                        .setMessage("Atual: " + currentVersion + "\nNova: " + offer.manifest.versionName + " (" + offer.manifest.versionCode
                                + ")\nTamanho: " + offer.manifest.size + " bytes\nA instalação reinicia o app.\n\n" + offer.notes)
                        .setNegativeButton("Cancelar", (d,w) -> updateFailed("Atualização cancelada."))
                        .setPositiveButton("Baixar", (d,w) -> downloadUpdate(offer))
                        .setOnCancelListener(d -> updateFailed("Atualização cancelada.")).show();
            }); }
        });
    }
    private void downloadUpdate(AppUpdater.Offer offer) {
        if (!updateActive || updater == null || !updateIdle()) { checkUpdate.setEnabled(true); prerelease.setEnabled(true); return; }
        updateStatus.setText("Baixando e verificando APK…"); AppUpdater request = updater;
        request.download(offer, true, true, new AppUpdater.DownloadCallback() {
            @Override public void failed(String reason) { runOnUiThread(() -> { if (updater == request) updateFailed(reason); }); }
            @Override public void verified(File apk) { runOnUiThread(() -> {
                if (!updateActive || updater != request) { apk.delete(); return; }
                pendingApk = apk;
                if (!updateIdle()) { discardPendingApk(); checkUpdate.setEnabled(true); prerelease.setEnabled(true); return; }
                updateDialog = new AlertDialog.Builder(VideoSettingsActivity.this).setTitle("Instalar atualização verificada?")
                        .setMessage("Versão " + offer.manifest.versionName + ". Tamanho, SHA-256 e certificado aprovados. O Android pedirá confirmação e reiniciará o app.")
                        .setNegativeButton("Cancelar", (d,w) -> { discardPendingApk(); updateFailed("Instalação cancelada."); })
                        .setPositiveButton("Instalar", (d,w) -> installUpdate(offer))
                        .setOnCancelListener(d -> { discardPendingApk(); updateFailed("Instalação cancelada."); }).show();
            }); }
        });
    }
    private void installUpdate(AppUpdater.Offer offer) {
        if (!updateActive || updater == null || pendingApk == null || !updateIdle()) {
            discardPendingApk(); checkUpdate.setEnabled(true); prerelease.setEnabled(true); return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            updateDialog = new AlertDialog.Builder(this).setTitle("Permitir instalação pelo Android")
                    .setMessage("Autorize este app na próxima tela. Ao voltar, verifique a atualização novamente para confirmar o download e a instalação.")
                    .setNegativeButton("Cancelar", (d,w) -> { discardPendingApk(); updateFailed("Instalação cancelada."); })
                    .setPositiveButton("Abrir permissão", (d,w) -> {
                        try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
                        catch (RuntimeException error) { discardPendingApk(); updateFailed("Permissão indisponível nesta central."); }
                    }).setOnCancelListener(d -> { discardPendingApk(); updateFailed("Instalação cancelada."); }).show();
            return;
        }
        AppUpdater request = updater; File apk = pendingApk;
        request.verifyAgain(apk, offer, new AppUpdater.DownloadCallback() {
            @Override public void failed(String reason) { runOnUiThread(() -> {
                if (updater == request) { discardPendingApk(); updateFailed(reason); }
            }); }
            @Override public void verified(File verified) { runOnUiThread(() -> {
                if (!updateActive || updater != request) { verified.delete(); return; }
                try {
                    if (!updateIdle()) { discardPendingApk(); checkUpdate.setEnabled(true); prerelease.setEnabled(true); return; }
                    if (getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode() >= offer.manifest.versionCode)
                        throw new IllegalStateException("A versão instalada já é igual ou mais nova.");
                    Uri uri = UpdateFileProvider.uriFor(VideoSettingsActivity.this, verified);
                    Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, UpdateFileProvider.MIME)
                            .setClipData(ClipData.newRawUri("Atualização", uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(install);
                    pendingApk = null; // Installer owns the read grant; retain the verified cache file.
                    updateFailed("Instalador Android aberto; confirme a instalação na tela do sistema.");
                } catch (Exception error) { discardPendingApk(); updateFailed("Não foi possível abrir o instalador: " + error.getMessage()); }
            }); }
        });
    }
    private void addToggle(LinearLayout content, String key, String label) {
        CheckBox box = new CheckBox(this); box.setText(label); toggles.put(key, box); content.addView(box);
    }
    private Spinner spinner(LinearLayout content, String label, String[] values) {
        TextView title = new TextView(this); title.setText(label); content.addView(title);
        Spinner spinner = new Spinner(this); spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values)); content.addView(spinner); return spinner;
    }
    private void load() {
        VideoSettings.Snapshot value = settings.snapshot();
        toggles.get("automaticRecording").setChecked(value.automaticRecording);
        toggles.get("recordParkedLive").setChecked(value.recordParkedLive);
        toggles.get("remoteLive").setChecked(value.remoteLive);
        toggles.get("remoteDownloads").setChecked(value.remoteDownloads);
        toggles.get("automaticDeletion").setChecked(value.automaticDeletion);
        toggles.get("overlayMaster").setChecked(value.overlayMaster);
        toggles.get("overlayDateTime").setChecked(value.overlayDateTime);
        toggles.get("overlaySpeed").setChecked(value.overlaySpeed);
        toggles.get("overlayAcceleration").setChecked(value.overlayAcceleration);
        toggles.get("overlayDriveState").setChecked(value.overlayDriveState);
        toggles.get("overlayDecelerationRegen").setChecked(value.overlayDecelerationRegen);
        quality.setSelection(value.quality.ordinal());
        reserve.setSelection(value.reserveBytes == (20L << 30) ? 2 : value.reserveBytes == (10L << 30) ? 1 : 0);
        position.setSelection(value.overlayPosition.ordinal());
    }
    private void save() {
        Bundle patch = new Bundle();
        for (Map.Entry<String, CheckBox> entry : toggles.entrySet()) patch.putBoolean(entry.getKey(), entry.getValue().isChecked());
        patch.putString("quality", quality.getSelectedItem().toString());
        patch.putInt("reserveGb", Integer.parseInt(reserve.getSelectedItem().toString()));
        patch.putString("overlayPosition", position.getSelectedItem().toString());
        Uri provider = Uri.parse("content://" + VideoCatalogProvider.AUTHORITY);
        Bundle current = getContentResolver().call(provider, "getSettings", null, null);
        Bundle extras = new Bundle();
        extras.putLong(VideoContract.EXTRA_EXPECTED_SETTINGS_REVISION,
                current.getLong(VideoContract.EXTRA_SETTINGS_REVISION));
        extras.putString(VideoContract.EXTRA_REQUEST_ID, "local-settings-" + System.currentTimeMillis());
        extras.putBundle(VideoContract.EXTRA_SETTINGS_PATCH, patch);
        Bundle result = getContentResolver().call(provider, "updateSettings", null, extras);
        if (result != null && result.getBoolean("applied")) finish();
    }
}
