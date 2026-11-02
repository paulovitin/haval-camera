package br.com.redesurftank.havalcamera;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.util.List;

/** Plays completed MP4 segments in order and blanks immediately on errors. */
public final class VideoPlaybackActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView surface; private TextView error; private List<VideoCatalog.Segment> segments;
    private VideoCatalog catalog; private String tripId; private int index; private MediaPlayer player;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); tripId = getIntent().getStringExtra("tripId");
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this); root.addView(surface, new FrameLayout.LayoutParams(-1,-1));
        error = new TextView(this); error.setTextColor(Color.WHITE);
        LinearLayout topLeft = new LinearLayout(this); topLeft.setOrientation(LinearLayout.VERTICAL);
        topLeft.addView(ActivityNavigation.backButton(this)); topLeft.addView(error);
        root.addView(topLeft, new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT));
        setContentView(root);
        try { catalog = VideoCatalog.shared(getFilesDir());
            segments = catalog.completedSnapshot().stream().filter(t -> Long.toString(t.tripId).equals(tripId)).findFirst().orElseThrow().segments;
            surface.getHolder().addCallback(this);
        } catch (Exception failure) { fail("Gravação indisponível"); }
    }
    private void play() {
        if (segments == null || index >= segments.size()) { finish(); return; }
        try {
            release(); File file = catalog.completedSegmentFile(tripId, Integer.toString(segments.get(index).segmentId));
            player = new MediaPlayer(); player.setDataSource(file.getPath()); player.setDisplay(surface.getHolder());
            player.setOnCompletionListener(value -> { index++; play(); }); player.setOnErrorListener((value, what, extra) -> { fail("Falha ao reproduzir"); return true; });
            player.prepare(); player.start();
        } catch (Exception failure) { fail("Falha ao reproduzir"); }
    }
    private void fail(String text) { release(); surface.setVisibility(SurfaceView.INVISIBLE); error.setText(text); }
    private void release() { if (player != null) { player.release(); player = null; } }
    @Override public void surfaceCreated(SurfaceHolder holder) { play(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { release(); }
    @Override protected void onDestroy() { release(); super.onDestroy(); }
}
