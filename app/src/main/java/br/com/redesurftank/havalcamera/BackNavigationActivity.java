package br.com.redesurftank.havalcamera;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;

abstract class BackNavigationActivity extends Activity {
    @Override public final void setContentView(View content) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(ActivityNavigation.backButton(this), new LinearLayout.LayoutParams(-2, -2));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        super.setContentView(root);
    }
}
