package br.com.redesurftank.havalcamera;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class ActivityNavigationTest {
    @Test public void backClickInvokesFinishOnce() {
        AtomicInteger finishes = new AtomicInteger();

        ActivityNavigation.backClick(finishes::incrementAndGet).onClick(null);

        assertEquals(1, finishes.get());
    }
}
