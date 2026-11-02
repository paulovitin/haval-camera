package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class ActivityNavigationTest {
    @Test public void everyScreenInheritsAutomaticBackLayout() {
        for (Class<?> screen : new Class<?>[]{CameraActivity.class, VideoGalleryActivity.class,
                VideoPlaybackActivity.class, VideoSettingsActivity.class}) {
            assertTrue(screen.getSimpleName(), BackNavigationActivity.class.isAssignableFrom(screen));
        }
    }

    @Test public void backButtonConfigurationLabelsAndFinishes() {
        AtomicInteger finishes = new AtomicInteger();
        AtomicReference<String> label = new AtomicReference<>();
        AtomicReference<View.OnClickListener> click = new AtomicReference<>();

        ActivityNavigation.configureBack(finishes::incrementAndGet, (text, listener) -> {
            label.set(text); click.set(listener);
        });

        assertEquals("Voltar", label.get());
        assertNotNull(click.get());
        click.get().onClick(null);
        assertEquals(1, finishes.get());
    }

    @Test public void backClickInvokesFinishOnce() {
        AtomicInteger finishes = new AtomicInteger();

        ActivityNavigation.backClick(finishes::incrementAndGet).onClick(null);

        assertEquals(1, finishes.get());
    }
}
