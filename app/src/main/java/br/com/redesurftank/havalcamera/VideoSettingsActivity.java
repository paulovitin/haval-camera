package br.com.redesurftank.havalcamera;

import android.app.Activity;
import android.os.Bundle;
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
public final class VideoSettingsActivity extends Activity {
    private VideoSettings settings;
    private final Map<String, CheckBox> toggles = new HashMap<>();
    private Spinner quality, reserve, position;

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
        ScrollView scroll = new ScrollView(this); scroll.addView(content); setContentView(scroll); load();
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
