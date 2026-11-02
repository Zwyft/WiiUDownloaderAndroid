package io.github.xpl0itu.wiiudownloader.android;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Renders the filtered title list with a queue checkbox per row. */
final class TitleAdapter extends BaseAdapter {

    private final Context context;
    private final List<Title> items = new ArrayList<>();
    private final Set<String> queued = new HashSet<>();

    TitleAdapter(Context context) {
        this.context = context;
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
        LinearLayout row;
        TextView nameView;
        TextView subtitleView;
        CheckBox checkBox;

        if (convertView == null) {
            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int padding = dp(12);
            row.setPadding(padding, padding, padding, padding);
            row.setMinimumHeight(dp(56));

            LinearLayout textColumn = new LinearLayout(context);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            textColumn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            nameView = new TextView(context);
            nameView.setTextSize(16);
            nameView.setTypeface(Typeface.DEFAULT_BOLD);
            textColumn.addView(nameView);

            subtitleView = new TextView(context);
            subtitleView.setTextSize(12);
            subtitleView.setAlpha(0.7f);
            textColumn.addView(subtitleView);

            checkBox = new CheckBox(context);
            checkBox.setFocusable(false);
            checkBox.setClickable(false);
            checkBox.setPadding(0, 0, 0, 0);

            row.addView(textColumn);
            row.addView(checkBox);

            row.setTag(new Object[]{nameView, subtitleView, checkBox});
            convertView = row;
        } else {
            Object[] holders = (Object[]) convertView.getTag();
            nameView = (TextView) holders[0];
            subtitleView = (TextView) holders[1];
            checkBox = (CheckBox) holders[2];
        }

        Title title = items.get(position);
        nameView.setText(title.displayName());
        subtitleView.setText(title.subtitle());
        checkBox.setChecked(queued.contains(title.titleId));
        return convertView;
    }

    private int dp(int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
