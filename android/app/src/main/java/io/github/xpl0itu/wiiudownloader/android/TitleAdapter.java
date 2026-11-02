package io.github.xpl0itu.wiiudownloader.android;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Renders the filtered title list as cards with a queue indicator per row. */
final class TitleAdapter extends BaseAdapter {

    private final Context context;
    private final List<Title> items = new ArrayList<>();
    private final Set<String> queued = new HashSet<>();

    private final int colorSurface;
    private final int colorSurfaceAlt;
    private final int colorStroke;
    private final int colorAccent;
    private final int colorBackground;
    private final int colorTextPrimary;
    private final int colorTextSecondary;
    private final Typeface medium;

    TitleAdapter(Context context) {
        this.context = context;
        colorSurface = context.getColor(R.color.surface);
        colorSurfaceAlt = context.getColor(R.color.surface_alt);
        colorStroke = context.getColor(R.color.stroke);
        colorAccent = context.getColor(R.color.accent);
        colorBackground = context.getColor(R.color.bg);
        colorTextPrimary = context.getColor(R.color.text_primary);
        colorTextSecondary = context.getColor(R.color.text_secondary);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    void setItems(List<Title> titles) {
        items.clear();
        items.addAll(titles);
        notifyDataSetChanged();
    }

    void setQueued(Set<String> titleIds) {
        queued.clear();
        queued.addAll(titleIds);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Title getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LinearLayout card;
        TextView nameView;
        TextView subtitleView;
        TextView indicator;

        if (convertView == null) {
            LinearLayout outer = new LinearLayout(context);
            outer.setOrientation(LinearLayout.VERTICAL);
            outer.setPadding(dp(0), 0, dp(0), dp(8));

            card = new LinearLayout(context);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(14), dp(12), dp(12), dp(12));

            LinearLayout textColumn = new LinearLayout(context);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            textColumn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            nameView = new TextView(context);
            nameView.setTextSize(15);
            nameView.setTextColor(colorTextPrimary);
            nameView.setTypeface(medium);
            nameView.setMaxLines(2);
            textColumn.addView(nameView);

            subtitleView = new TextView(context);
            subtitleView.setTextSize(11);
            subtitleView.setTextColor(colorTextSecondary);
            subtitleView.setLetterSpacing(0.02f);
            subtitleView.setMaxLines(1);
            LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subtitleParams.topMargin = dp(3);
            subtitleView.setLayoutParams(subtitleParams);
            textColumn.addView(subtitleView);

            indicator = new TextView(context);
            indicator.setTextSize(13);
            indicator.setGravity(Gravity.CENTER);
            indicator.setTypeface(medium);
            LinearLayout.LayoutParams indicatorParams = new LinearLayout.LayoutParams(dp(28), dp(28));
            indicatorParams.leftMargin = dp(10);
            indicator.setLayoutParams(indicatorParams);

            card.addView(textColumn);
            card.addView(indicator);
            outer.addView(card, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            outer.setTag(new Object[]{card, nameView, subtitleView, indicator});
            convertView = outer;
        } else {
            Object[] holders = (Object[]) convertView.getTag();
            card = (LinearLayout) holders[0];
            nameView = (TextView) holders[1];
            subtitleView = (TextView) holders[2];
            indicator = (TextView) holders[3];
        }

        Title title = items.get(position);
        boolean isQueued = queued.contains(title.titleId);

        nameView.setText(title.displayName());
        subtitleView.setText(title.subtitle());
        card.setBackground(rowBackground(isQueued));

        if (isQueued) {
            indicator.setText("✓");
            indicator.setTextColor(colorBackground);
            indicator.setBackground(rounded(colorAccent, 10));
        } else {
            indicator.setText("");
            indicator.setBackground(rounded(0x00000000, 0));
            indicator.setBackground(outline(0x00000000, colorStroke, 10));
        }
        indicator.setContentDescription(isQueued ? "Queued" : "Not queued");
        return convertView;
    }

    private Drawable rowBackground(boolean isQueued) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                outline(colorSurfaceAlt, isQueued ? colorAccent : colorStroke, 14));
        states.addState(new int[]{},
                outline(colorSurface, isQueued ? colorAccent : colorStroke, 14));
        return states;
    }

    private GradientDrawable rounded(int fillColor, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp) * 1f);
        return drawable;
    }

    private GradientDrawable outline(int fillColor, int strokeColor, int radiusDp) {
        GradientDrawable drawable = rounded(fillColor, radiusDp);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private int dp(int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
