package br.com.redesurftank.havalcamera;

import android.view.View;

public final class ActivityNavigation {
    private ActivityNavigation() { }

    static View.OnClickListener backClick(Runnable finish) {
        return view -> { };
    }
}
