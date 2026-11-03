package br.com.redesurftank.havalcamera;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import java.io.IOException;

/** Same-process-only destructive actions confirmed by the local UI. */
public final class LocalVideoActionService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            VideoCatalog catalog = VideoCatalog.shared(getFilesDir());
            for (VideoCatalog.Trip trip : catalog.completedSnapshot()) {
                if (!catalog.leasedTripIds().contains(trip.tripId))
                    catalog.discardTrip(Long.toString(trip.tripId));
            }
        } catch (IOException failure) {
            android.util.Log.e("HavalCamera", "Local deletion failed", failure);
        } finally {
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
