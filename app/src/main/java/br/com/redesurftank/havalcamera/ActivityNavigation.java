package br.com.redesurftank.havalcamera;

import android.app.Activity;
import android.view.View;
import android.widget.Button;

public final class ActivityNavigation {
    private ActivityNavigation() { }

    public static Button backButton(Activity activity) {
        Button button = new Button(activity);
        button.setText("Voltar");
        button.setOnClickListener(backClick(activity::finish));
        return button;
    }

    static View.OnClickListener backClick(Runnable finish) {
        return view -> finish.run();
    }
}
