package br.com.redesurftank.havalcamera;

import android.app.Activity;
import android.view.View;
import android.widget.Button;
import java.util.function.BiConsumer;

public final class ActivityNavigation {
    private ActivityNavigation() { }

    public static Button backButton(Activity activity) {
        Button button = new Button(activity);
        configureBack(activity::finish, (label, click) -> {
            button.setText(label);
            button.setOnClickListener(click);
        });
        button.setOnClickListener(view -> activity.recreate());
        return button;
    }

    static View.OnClickListener backClick(Runnable finish) {
        return view -> finish.run();
    }

    static void configureBack(Runnable finish, BiConsumer<String, View.OnClickListener> control) {
        control.accept("Voltar", backClick(finish));
    }
}
