package ir.rahyab.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** منوی کشویی پایین صفحه. */
final class Sheet {
    private final Activity activity;
    private final Dialog dialog;
    private final LinearLayout rows;

    Sheet(Activity activity, String title, String subtitle) {
        this.activity = activity;
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundResource(R.drawable.bg_sheet);
        root.setPadding(dp(18), dp(12), dp(18), dp(22));

        View handle = new View(activity);
        handle.setBackgroundResource(R.drawable.bg_chip);
        LinearLayout.LayoutParams h = new LinearLayout.LayoutParams(dp(48), dp(5));
        h.gravity = Gravity.CENTER_HORIZONTAL;
        h.bottomMargin = dp(12);
        root.addView(handle, h);

        root.addView(text(title, 19, Color.WHITE, true));
        if (subtitle != null) {
            TextView s = text(subtitle, 13, 0xFF9FB8C4, false);
            s.setTextIsSelectable(true);
            root.addView(s);
        }
        rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(rows);
        root.addView(scroll);
        dialog.setContentView(root);
    }

    Sheet row(String icon, String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_row);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView i = text(icon, 20, Color.WHITE, false);
        i.setGravity(Gravity.CENTER);
        row.addView(i, new LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(10), 0, dp(10), 0);
        column.addView(text(title, 15, Color.WHITE, true));
        if (subtitle != null) {
            column.addView(text(subtitle, 12, 0xFF9FB8C4, false));
        }
        row.addView(column, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(v -> {
            dialog.dismiss();
            action.run();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(8);
        rows.addView(row, p);
        return this;
    }

    void show() {
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(activity);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
