package io.github.xpl0itu.wiiudownloader.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import mobile.Mobile;

/**
 * Single-screen Android front end for the WiiUDownloader core.
 *
 * <p>The Go core (bound through gomobile, see mobile/mobile.go) does all
 * downloading and decryption. Transfers run inside {@link DownloadService} so
 * they survive the app being backgrounded; this activity browses titles, builds
 * the queue and polls {@code Mobile.progress()} for the live numbers.
 *
 * <p>The UI is built in code against {@code AppTheme} (dark surfaces, one accent
 * colour, rounded pills) rather than AndroidX/Material, because the app links
 * only the platform framework.
 */
public final class MainActivity extends Activity {

    private static final String PREFS = "wiiudownloader";
    private static final String PREF_OUTPUT_DIR = "output_dir";
    private static final String PREF_DECRYPT = "decrypt";
    private static final String PREF_DELETE_ENCRYPTED = "delete_encrypted";
    private static final String PREF_CATALOG_URL = "catalog_url";
    private static final String PREF_DATABASE = "database_json"; // legacy copy kept in prefs

    /** Title list is stored next to app data, not in preferences: it is megabytes. */
    private static final String DATABASE_FILE = "titles.json";

    /**
     * Wii U eShop catalogue published by the nus-info project: title IDs, names
     * and regions for the whole store. CC BY-NC 4.0, see nus-info's README.
     */
    private static final String CATALOG_URL =
            "https://dantheman827.github.io/nus-info/complete-wup-regionprimaries.json";

    private static final int REQUEST_IMPORT_DB = 1001;
    private static final int REQUEST_DECRYPT_TREE = 1002;
    private static final int REQUEST_OUTPUT_TREE = 1003;
    private static final int REQUEST_NOTIFICATIONS = 1004;
    private static final int REQUEST_STORAGE = 1005;

    /** Cap on rows sent to the list; the catalogue holds thousands of titles. */
    private static final int RESULT_LIMIT = 400;

    private static final String[] CATEGORY_LABELS = {"All", "Games", "Updates", "DLC", "Demos"};
    // Matches TITLE_CATEGORY_* in the Go core.
    private static final int[] CATEGORY_VALUES = {4, 0, 1, 2, 3};

    private static final String[] REGION_LABELS = {"All regions", "USA", "Europe", "Japan"};
    // Matches MCP_REGION_* masks in the Go core; 0 means "no filter".
    private static final int[] REGION_VALUES = {0, 2, 4, 1};

    private EditText searchBox;
    private ListView listView;
    private TextView emptyStateView;
    private TextView statusView;
    private TextView queueSummaryView;
    private ProgressBar progressBar;
    private TextView downloadButton;
    private TextView cancelButton;
    private CheckBox decryptCheckBox;
    private CheckBox deleteEncryptedCheckBox;
    private TitleAdapter adapter;

    private final List<TextView> categoryChips = new ArrayList<>();
    private final List<TextView> regionChips = new ArrayList<>();
    private int categoryIndex;
    private int regionIndex;

    private final Map<String, Title> queue = new LinkedHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private String outputDirectory;
    private boolean polling;

    /** Folder waiting for storage access before it can be used. */
    private String pendingPath;
    private boolean pendingIsDecrypt;
    private boolean catalogLoading;

    private final Runnable progressPoll = new Runnable() {
        @Override
        public void run() {
            if (!polling) {
                return;
            }
            try {
                JSONObject progress = new JSONObject(Mobile.progress());
                double percent = progress.optDouble("percent", 0d);
                progressBar.setProgress((int) Math.round(percent * 100d));

                String phase = progress.optString("phase", "");
                String message = progress.optString("message", "");
                String error = progress.optString("error", "");

                if ("done".equals(phase)) {
                    setStatus("Finished", R.color.text_primary);
                } else if ("error".equals(phase) || (!error.isEmpty() && !"idle".equals(phase))) {
                    setStatus("Error: " + error, R.color.danger);
                } else if ("cancelled".equals(phase)) {
                    setStatus("Cancelled", R.color.text_secondary);
                } else {
                    long downloaded = progress.optLong("downloaded", 0L);
                    long total = progress.optLong("total", 0L);
                    long speed = progress.optLong("speed", 0L);
                    StringBuilder builder = new StringBuilder();
                    String title = progress.optString("title", "");
                    if (!title.isEmpty()) {
                        builder.append(title).append(" — ");
                    }
                    builder.append(message.isEmpty() ? "Working…" : message);
                    if (total > 0) {
                        builder.append(String.format(Locale.US, "  %s / %s", formatBytes(downloaded), formatBytes(total)));
                    }
                    if (speed > 0) {
                        builder.append("  ").append(formatBytes(speed)).append("/s");
                    }
                    setStatus(builder.toString(), R.color.text_primary);
                }
            } catch (Exception ignored) {
                // A progress snapshot can race with the end of a run; the next
                // tick or the run's completion handler settles the UI.
            }

            if (DownloadService.isRunning()) {
                handler.postDelayed(this, 300);
            } else {
                settleRun();
            }
        }
    };

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        File defaultDir = new File(getExternalFilesDir(null), "WiiUDownloader");
        outputDirectory = prefs.getString(PREF_OUTPUT_DIR, defaultDir.getAbsolutePath());

        buildLayout();

        decryptCheckBox.setChecked(prefs.getBoolean(PREF_DECRYPT, true));
        deleteEncryptedCheckBox.setChecked(prefs.getBoolean(PREF_DELETE_ENCRYPTED, false));

        loadStoredDatabase();
        refreshList();

        if (DownloadService.isRunning()) {
            polling = true;
            updateQueueUi();
            handler.post(progressPoll);
        } else if (Mobile.databaseCount() == 0 && !catalogLoading) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    promptForCatalog();
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        retryPendingPath();
        if (DownloadService.isRunning() && !polling) {
            polling = true;
            updateQueueUi();
            handler.post(progressPoll);
        }
    }

    @Override
    protected void onDestroy() {
        polling = false;
        handler.removeCallbacks(progressPoll);
        // Transfers live in DownloadService, so shutting the UI executor down
        // does not interrupt a running download.
        executor.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- layout

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.bg));
        root.setPadding(dp(16), dp(14), dp(16), dp(12));
        root.setFitsSystemWindows(true);

        root.addView(buildHeader());
        root.addView(buildSearchField());
        root.addView(buildFilterRow());
        root.addView(buildListArea());
        root.addView(buildToggles());
        root.addView(buildTransferCard());
        root.addView(buildRunRow());
        root.addView(buildQuickActions());

        setContentView(root);
        updateQueueUi();
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(14));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = text("WiiUDownloader", 22, R.color.text_primary);
        title.setTypeface(medium());
        titles.addView(title);

        TextView subtitle = text("Wii U titles · core " + Mobile.version(), 11, R.color.text_secondary);
        subtitle.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(2);
        subtitle.setLayoutParams(subtitleParams);
        titles.addView(subtitle);
        header.addView(titles);

        TextView menuButton = new TextView(this);
        menuButton.setText("⋮");
        menuButton.setTextSize(20);
        menuButton.setTextColor(color(R.color.text_primary));
        menuButton.setGravity(Gravity.CENTER);
        menuButton.setBackground(pressable(color(R.color.surface_alt), color(R.color.stroke), 22));
        menuButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showMenu(view);
            }
        });
        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        menuButton.setLayoutParams(menuParams);
        header.addView(menuButton);
        return header;
    }

    private View buildSearchField() {
        searchBox = new EditText(this);
        searchBox.setHint("Search titles or a title ID");
        searchBox.setHintTextColor(color(R.color.text_secondary));
        searchBox.setTextColor(color(R.color.text_primary));
        searchBox.setTextSize(15);
        searchBox.setSingleLine(true);
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT);
        searchBox.setBackground(outline(color(R.color.surface), color(R.color.stroke), 16));
        searchBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refreshList();
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        searchBox.setLayoutParams(params);
        return searchBox;
    }

    private View buildFilterRow() {
        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.VERTICAL);

        filters.addView(chipScroller(CATEGORY_LABELS, categoryChips, true));
        filters.addView(chipScroller(REGION_LABELS, regionChips, false));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(6);
        filters.setLayoutParams(params);
        return filters;
    }

    private View chipScroller(String[] labels, final List<TextView> target, final boolean categoryRow) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView chip = new TextView(this);
            chip.setText(labels[i]);
            chip.setTextSize(12);
            chip.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = dp(8);
            chip.setLayoutParams(params);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (categoryRow) {
                        selectCategory(index);
                    } else {
                        selectRegion(index);
                    }
                }
            });
            styleChip(chip, i == 0);
            target.add(chip);
            row.addView(chip);
        }

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        scroller.addView(row);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        scroller.setLayoutParams(params);
        return scroller;
    }

    private void selectCategory(int index) {
        categoryIndex = index;
        for (int i = 0; i < categoryChips.size(); i++) {
            styleChip(categoryChips.get(i), i == index);
        }
        refreshList();
    }

    private void selectRegion(int index) {
        regionIndex = index;
        for (int i = 0; i < regionChips.size(); i++) {
            styleChip(regionChips.get(i), i == index);
        }
        refreshList();
    }

    private void styleChip(TextView chip, boolean selected) {
        chip.setTypeface(selected ? medium() : Typeface.DEFAULT);
        chip.setTextColor(selected ? color(R.color.bg) : color(R.color.text_secondary));
        chip.setBackground(selected
                ? pressable(color(R.color.accent), color(R.color.accent_dim), 18)
                : pressable(color(R.color.surface_alt), color(R.color.stroke), 18));
        chip.setPadding(dp(14), dp(7), dp(14), dp(7));
    }

    private View buildListArea() {
        FrameLayout container = new FrameLayout(this);
        container.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setSelector(new ColorDrawable(0x00000000));
        listView.setCacheColorHint(0x00000000);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(2), 0, dp(8));
        listView.setVerticalScrollBarEnabled(false);
        adapter = new TitleAdapter(this);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                toggleQueue(adapter.getItem(position));
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showTitleDetails(adapter.getItem(position));
                return true;
            }
        });

        emptyStateView = text("", 13, R.color.text_secondary);
        emptyStateView.setGravity(Gravity.CENTER);
        emptyStateView.setPadding(dp(24), dp(24), dp(24), dp(24));

        container.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.addView(emptyStateView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listView.setEmptyView(emptyStateView);
        return container;
    }

    private View buildToggles() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        decryptCheckBox = toggle("Decrypt", PREF_DECRYPT);
        decryptCheckBox.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(decryptCheckBox);

        deleteEncryptedCheckBox = toggle("Delete encrypted", PREF_DELETE_ENCRYPTED);
        deleteEncryptedCheckBox.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(deleteEncryptedCheckBox);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        row.setLayoutParams(params);
        return row;
    }

    private CheckBox toggle(String label, final String prefKey) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextSize(11);
        box.setTextColor(color(R.color.text_secondary));
        box.setButtonTintList(tint(R.color.accent));
        box.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(prefKey, isChecked).apply();
            }
        });
        return box;
    }

    private View buildTransferCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(outline(color(R.color.surface), color(R.color.stroke), 18));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(10);
        card.setLayoutParams(cardParams);

        TextView label = text("TRANSFER", 10, R.color.text_secondary);
        label.setLetterSpacing(0.14f);
        label.setTypeface(medium());
        card.addView(label);

        statusView = text("Ready", 13, R.color.text_primary);
        statusView.setMaxLines(2);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(4);
        statusView.setLayoutParams(statusParams);
        card.addView(statusView);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        Drawable track = getResources().getDrawable(R.drawable.progress_horizontal, getTheme());
        progressBar.setProgressDrawable(track);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        barParams.topMargin = dp(10);
        barParams.bottomMargin = dp(8);
        progressBar.setLayoutParams(barParams);
        card.addView(progressBar);

        queueSummaryView = text("", 11, R.color.text_secondary);
        card.addView(queueSummaryView);
        return card;
    }

    private View buildRunRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        downloadButton = pill("Download queue (0)", R.color.accent, R.color.bg);
        LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        downloadParams.rightMargin = dp(8);
        downloadButton.setLayoutParams(downloadParams);
        downloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startDownloadRun();
            }
        });
        row.addView(downloadButton);

        cancelButton = pill("Cancel", R.color.surface_alt, R.color.text_secondary);
        cancelButton.setLayoutParams(new LinearLayout.LayoutParams(dp(104), dp(48)));
        cancelButton.setEnabled(false);
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                DownloadService.requestCancel();
                setStatus("Cancelling…", R.color.text_secondary);
            }
        });
        row.addView(cancelButton);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        row.setLayoutParams(params);
        return row;
    }

    private View buildQuickActions() {
        final String[] labels = {"Title list", "Add ID", "Folder", "Decrypt", "Clear"};
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            final int action = i;
            TextView chip = text(labels[i], 12, R.color.text_secondary);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(pressable(color(R.color.surface_alt), color(R.color.stroke), 18));
            chip.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = dp(8);
            chip.setLayoutParams(params);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    switch (action) {
                        case 0:
                            importCatalog();
                            break;
                        case 1:
                            showAddTitleDialog();
                            break;
                        case 2:
                            pickFolder(REQUEST_OUTPUT_TREE);
                            break;
                        case 3:
                            pickFolder(REQUEST_DECRYPT_TREE);
                            break;
                        default:
                            queue.clear();
                            updateQueueUi();
                            break;
                    }
                }
            });
            row.addView(chip);
        }

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(row);
        return scroller;
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Download title list…");
        menu.getMenu().add(0, 2, 1, "Import title list file…");
        menu.getMenu().add(0, 3, 2, "Decrypt folder…");
        menu.getMenu().add(0, 4, 3, "Output folder…");
        menu.getMenu().add(0, 5, 4, "About");
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        importCatalog();
                        return true;
                    case 2:
                        importDatabase();
                        return true;
                    case 3:
                        pickFolder(REQUEST_DECRYPT_TREE);
                        return true;
                    case 4:
                        pickFolder(REQUEST_OUTPUT_TREE);
                        return true;
                    case 5:
                        showAbout();
                        return true;
                    default:
                        return false;
                }
            }
        });
        menu.show();
    }

    // ------------------------------------------------------------ title list

    private void refreshList() {
        int category = CATEGORY_VALUES[Math.max(0, Math.min(categoryIndex, CATEGORY_VALUES.length - 1))];
        int region = REGION_VALUES[Math.max(0, Math.min(regionIndex, REGION_VALUES.length - 1))];
        String query = searchBox.getText().toString();

        List<Title> titles = new ArrayList<>();
        try {
            String json = Mobile.searchTitles(query, category, region, RESULT_LIMIT);
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                titles.add(Title.fromJson(array.getJSONObject(i)));
            }
        } catch (Exception error) {
            toast("Search failed: " + error.getMessage());
        }

        adapter.setItems(titles);

        if (emptyStateView != null) {
            if (Mobile.databaseCount() == 0) {
                emptyStateView.setText("No title list yet.\nTap “Title list” to download the eShop catalogue, or add a title ID.");
            } else {
                emptyStateView.setText("No titles match this filter.");
            }
        }

        if (titles.size() >= RESULT_LIMIT) {
            queueSummaryView.setText("Showing the first " + RESULT_LIMIT + " matches — narrow the search to see more.");
        } else if (queue.isEmpty()) {
            queueSummaryView.setText("Output: " + outputDirectory);
        }
    }

    private void toggleQueue(Title title) {
        if (queue.containsKey(title.titleId)) {
            queue.remove(title.titleId);
        } else {
            queue.put(title.titleId, title);
        }
        updateQueueUi();
    }

    private void updateQueueUi() {
        boolean busy = DownloadService.isRunning();
        boolean canDownload = !busy && !queue.isEmpty();
        Set<String> ids = new LinkedHashSet<>(queue.keySet());
        adapter.setQueued(ids);
        downloadButton.setText("Download queue (" + queue.size() + ")");
        downloadButton.setEnabled(canDownload);
        downloadButton.setAlpha(canDownload ? 1f : 0.45f);
        cancelButton.setEnabled(busy);
        cancelButton.setAlpha(busy ? 1f : 0.45f);
        queueSummaryView.setText(queue.isEmpty()
                ? "Output: " + outputDirectory
                : queue.size() + " queued · output: " + outputDirectory);
    }

    private void setStatus(String message, int colorRes) {
        statusView.setText(message);
        statusView.setTextColor(color(colorRes));
    }

    // ------------------------------------------------------------- database

    private File databaseFile() {
        return new File(getFilesDir(), DATABASE_FILE);
    }

    private void loadStoredDatabase() {
        final File file = databaseFile();

        // Older builds kept the JSON inside preferences; move it to a file.
        String legacy = prefs.getString(PREF_DATABASE, null);
        if (!file.isFile() && legacy != null && !legacy.isEmpty()) {
            try {
                saveDatabase(legacy);
                prefs.edit().remove(PREF_DATABASE).apply();
            } catch (Exception ignored) {
                // Fall through: the file simply stays missing.
            }
        }
        if (!file.isFile()) {
            return;
        }

        catalogLoading = true;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                String failure = "";
                long count = 0;
                try {
                    count = importAny(readText(file));
                } catch (Exception error) {
                    failure = error.getMessage() == null ? error.toString() : error.getMessage();
                }
                final String message = failure;
                final long loaded = count;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        catalogLoading = false;
                        if (!message.isEmpty()) {
                            setStatus("Stored title list could not be loaded: " + message, R.color.danger);
                        } else if (loaded > 0) {
                            setStatus("Loaded " + loaded + " titles.", R.color.text_secondary);
                        }
                        refreshList();
                    }
                });
            }
        });
    }

    private void saveDatabase(String json) throws Exception {
        FileOutputStream output = new FileOutputStream(databaseFile());
        try {
            output.write(json.getBytes("UTF-8"));
        } finally {
            output.close();
        }
    }

    /** Accepts both the eShop catalogue and a plain array of titles. */
    private long importAny(String json) throws Exception {
        try {
            return Mobile.importEshopCatalogJSON(json);
        } catch (Exception catalogError) {
            return Mobile.importTitleDatabaseJSON(json);
        }
    }

    private void promptForCatalog() {
        if (isFinishing() || Mobile.databaseCount() > 0) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("No title list yet")
                .setMessage("Search needs a title list. Download the public Wii U eShop catalogue (about 8 MB, one time) now?\n\n"
                        + "You can also queue a title by its ID without any list.")
                .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        downloadCatalog(CATALOG_URL);
                    }
                })
                .setNeutralButton("Add ID", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showAddTitleDialog();
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    private void importCatalog() {
        final EditText input = dialogInput(prefs.getString(PREF_CATALOG_URL, CATALOG_URL));

        new AlertDialog.Builder(this)
                .setTitle("Download title list")
                .setMessage("Fetches the Wii U eShop catalogue published by the nus-info project "
                        + "(title IDs, names and regions; CC BY-NC 4.0). Leave the URL unless you have a mirror.")
                .setView(input)
                .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String url = input.getText().toString().trim();
                        if (url.isEmpty()) {
                            url = CATALOG_URL;
                        }
                        prefs.edit().putString(PREF_CATALOG_URL, url).apply();
                        downloadCatalog(url);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void downloadCatalog(final String url) {
        if (catalogLoading) {
            toast("A title list download is already running");
            return;
        }
        catalogLoading = true;
        setStatus("Downloading title list…", R.color.text_primary);
        toast("Downloading the title list, this takes a moment");

        executor.execute(new Runnable() {
            @Override
            public void run() {
                String failure = "";
                long count = 0;
                try {
                    String json = fetchText(url);
                    count = importAny(json);
                    saveDatabase(json);
                } catch (Exception error) {
                    failure = error.getMessage() == null ? error.toString() : error.getMessage();
                }
                final String message = failure;
                final long loaded = count;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        catalogLoading = false;
                        if (!message.isEmpty()) {
                            setStatus("Title list download failed: " + message, R.color.danger);
                            toast("Title list download failed: " + message);
                            return;
                        }
                        refreshList();
                        setStatus("Loaded " + loaded + " titles.", R.color.success);
                        toast("Loaded " + loaded + " titles");
                    }
                });
            }
        });
    }

    private String fetchText(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(180000);
        connection.setRequestProperty("User-Agent", "WiiUDownloader-Android");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + code + " from " + url);
            }
            InputStream input = connection.getInputStream();
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream(8 * 1024 * 1024);
                byte[] chunk = new byte[64 * 1024];
                int read;
                while ((read = input.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                return buffer.toString("UTF-8");
            } finally {
                input.close();
            }
        } finally {
            connection.disconnect();
        }
    }

    private void importDatabase() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_IMPORT_DB);
        } catch (Exception error) {
            toast("No file picker available");
        }
    }

    // --------------------------------------------------------------- dialogs

    private void showAddTitleDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        container.setPadding(padding, dp(8), padding, 0);

        final EditText idBox = dialogInput("");
        idBox.setHint("0005000010101d00");
        container.addView(idBox);

        final EditText versionBox = dialogInput("");
        versionBox.setHint("Title version (blank = latest)");
        versionBox.setInputType(InputType.TYPE_CLASS_NUMBER);
        container.addView(versionBox);

        new AlertDialog.Builder(this)
                .setTitle("Add title by ID")
                .setView(container)
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        addTitleFromId(idBox.getText().toString(), versionBox.getText().toString());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EditText dialogInput(String value) {
        EditText input = new EditText(this);
        input.setText(value);
        input.setTextSize(14);
        input.setSingleLine(false);
        input.setBackground(outline(color(R.color.surface_alt), color(R.color.stroke), 12));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        return input;
    }

    private void addTitleFromId(String rawId, String rawVersion) {
        try {
            JSONObject json = new JSONObject(Mobile.titleInfo(rawId.trim()));
            int version = -1;
            String trimmedVersion = rawVersion.trim();
            if (!trimmedVersion.isEmpty()) {
                version = Integer.parseInt(trimmedVersion);
            }
            Title title = new Title(
                    json.optString("titleId"),
                    json.optString("name", ""),
                    json.optInt("region", 0),
                    json.optString("regionName", "Unknown"),
                    json.optString("kind", "Game"),
                    json.optInt("category", 0),
                    version);
            queue.put(title.titleId, title);
            updateQueueUi();
            toast("Queued " + title.displayName());
        } catch (Exception error) {
            toast("Invalid title ID: " + error.getMessage());
        }
    }

    private void showTitleDetails(final Title title) {
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title.displayName())
                .setMessage(title.subtitle() + "\n\nFetching size…")
                .setPositiveButton("Queue", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        toggleQueue(title);
                    }
                })
                .setNegativeButton("Close", null)
                .create();
        dialog.show();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                String text;
                try {
                    long size = Mobile.titleSize(title.titleId, title.version);
                    text = title.subtitle() + "\n\nDownload size: " + formatBytes(size);
                } catch (Exception error) {
                    text = title.subtitle() + "\n\nSize unavailable: " + error.getMessage();
                }
                final String message = text;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        dialog.setMessage(message);
                    }
                });
            }
        });
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("WiiUDownloader for Android")
                .setMessage("Downloads Wii U titles from Nintendo's CDN and decrypts them.\n\n"
                        + "Core: " + Mobile.version() + "\n"
                        + "Titles in list: " + Mobile.databaseCount() + "\n"
                        + "Output folder: " + outputDirectory + "\n\n"
                        + "The desktop title feed (napi.v10lator.de) is offline, so the app downloads the "
                        + "Wii U eShop catalogue published by the nus-info project "
                        + "(CC BY-NC 4.0) instead. You can also import a title list file or add a title by ID.\n\n"
                        + "GPLv3 · based on WiiUDownloader by Xpl0itU")
                .setPositiveButton("OK", null)
                .show();
    }

    // ------------------------------------------------------------- transfers

    private void startDownloadRun() {
        if (DownloadService.isRunning() || queue.isEmpty()) {
            return;
        }
        File directory = new File(outputDirectory);
        if (!directory.exists() && !directory.mkdirs()) {
            toast("Cannot create " + outputDirectory);
            return;
        }

        final boolean decrypt = decryptCheckBox.isChecked();
        final boolean deleteEncrypted = deleteEncryptedCheckBox.isChecked();
        List<Title> batch = new ArrayList<>(queue.values());

        DownloadService.startDownloads(this, batch, outputDirectory, decrypt, deleteEncrypted);
        ensureNotificationPermission();

        updateQueueUi();
        progressBar.setProgress(0);
        setStatus("Starting…", R.color.text_primary);
        polling = true;
        handler.post(progressPoll);
    }

    private void startDecryptFolder(String path) {
        if (DownloadService.isRunning()) {
            toast("A transfer is already running");
            return;
        }
        DownloadService.startDecrypt(this, path, deleteEncryptedCheckBox.isChecked());
        ensureNotificationPermission();

        updateQueueUi();
        progressBar.setProgress(0);
        setStatus("Decrypting " + new File(path).getName() + "…", R.color.text_primary);
        polling = true;
        handler.post(progressPoll);
    }

    private void settleRun() {
        polling = false;
        handler.removeCallbacks(progressPoll);

        List<String> finished = DownloadService.finishedTitleIds();
        for (String titleId : finished) {
            queue.remove(titleId);
        }
        updateQueueUi();

        String error = DownloadService.lastError();
        int failures = DownloadService.failures();
        boolean decryptRun = DownloadService.MODE_DECRYPT.equals(DownloadService.lastMode());

        if (DownloadService.wasCancelled()) {
            setStatus("Cancelled", R.color.text_secondary);
            progressBar.setProgress(0);
        } else if (failures > 0 || !error.isEmpty()) {
            setStatus("Finished with " + Math.max(failures, 1) + " failure(s). " + error, R.color.danger);
            progressBar.setProgress(0);
        } else if (decryptRun) {
            setStatus("Decryption finished", R.color.success);
            progressBar.setProgress(100);
        } else {
            setStatus("Finished — saved to " + outputDirectory, R.color.success);
            progressBar.setProgress(100);
        }
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
    }

    // -------------------------------------------------------- folder picking

    private void pickFolder(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, requestCode);
        } catch (Exception error) {
            toast("No folder picker available: " + (error.getMessage() == null ? error.toString() : error.getMessage()));
        }
    }

    private void usePickedFolder(int requestCode, Uri treeUri) {
        try {
            getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
            // Some providers only hand out a one-shot grant; the path still works.
        }

        String path = resolveTreePath(treeUri);
        if (path == null) {
            toast("That folder has no filesystem path. Pick a folder on internal storage or an SD card.");
            return;
        }

        if (requestCode == REQUEST_DECRYPT_TREE) {
            if (!canReadDirectory(path)) {
                requestStorageAccess(path, true);
                return;
            }
            startDecryptFolder(path);
        } else if (requestCode == REQUEST_OUTPUT_TREE) {
            if (!canWriteDirectory(path)) {
                requestStorageAccess(path, false);
                return;
            }
            applyOutputDirectory(path);
        }
    }

    private void applyOutputDirectory(String path) {
        outputDirectory = path;
        prefs.edit().putString(PREF_OUTPUT_DIR, path).apply();
        updateQueueUi();
        toast("Downloads go to " + path);
    }

    /**
     * Maps a SAF tree URI onto a real path. Filesystem paths are what the Go core
     * works with, so the picker is only a way to choose a directory.
     */
    private String resolveTreePath(Uri treeUri) {
        try {
            String documentId = DocumentsContract.getTreeDocumentId(treeUri);
            int colon = documentId.indexOf(':');
            if (colon < 0) {
                return null;
            }
            String volume = documentId.substring(0, colon);
            String relative = documentId.substring(colon + 1);
            File root = volumeRoot(volume);
            if (root == null) {
                return null;
            }
            File target = relative.isEmpty() ? root : new File(root, relative);
            return target.getAbsolutePath();
        } catch (Exception error) {
            return null;
        }
    }

    private File volumeRoot(String volume) {
        if (volume.isEmpty() || "primary".equalsIgnoreCase(volume)) {
            return Environment.getExternalStorageDirectory();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            StorageManager manager = getSystemService(StorageManager.class);
            if (manager != null) {
                for (StorageVolume candidate : manager.getStorageVolumes()) {
                    if (volume.equalsIgnoreCase(candidate.getUuid())) {
                        File directory = candidate.getDirectory();
                        if (directory != null) {
                            return directory;
                        }
                    }
                }
            }
        }
        File fallback = new File("/storage/" + volume);
        return fallback.isDirectory() ? fallback : null;
    }

    private boolean canReadDirectory(String path) {
        File directory = new File(path);
        if (!directory.isDirectory()) {
            return false;
        }
        File[] children = directory.listFiles();
        if (children != null) {
            return true;
        }
        // An empty but readable folder is fine; an unreadable one is not.
        return directory.canRead();
    }

    private boolean canWriteDirectory(String path) {
        File directory = new File(path);
        if (!directory.isDirectory() || !directory.canWrite()) {
            return false;
        }
        File probe = new File(directory, ".wiiudownloader_probe");
        try {
            return probe.createNewFile() || probe.exists();
        } catch (Exception error) {
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
        }
    }

    // --------------------------------------------------------- storage access

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess(String path, boolean isDecrypt) {
        if (hasStorageAccess()) {
            toast("Cannot use " + path + ": check that the folder exists and is not read-only.");
            return;
        }
        pendingPath = path;
        pendingIsDecrypt = isDecrypt;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Storage access needed")
                .setMessage("Android only lets apps read folders they do not own (such as Download or an SD card) "
                        + "when they have \"All files access\". Grant it, come back, and the folder is used automatically.")
                .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openAllFilesSettings();
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pendingPath = null;
                    }
                })
                .show();
    }

    private void openAllFilesSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception error) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {
                toast("Could not open the storage permission screen");
            }
        }
    }

    private void retryPendingPath() {
        if (pendingPath == null || !hasStorageAccess()) {
            return;
        }
        String path = pendingPath;
        boolean isDecrypt = pendingIsDecrypt;
        pendingPath = null;

        if (isDecrypt) {
            if (!canReadDirectory(path)) {
                toast("Still cannot read " + path);
                return;
            }
            startDecryptFolder(path);
        } else {
            if (!canWriteDirectory(path)) {
                toast("Still cannot write to " + path);
                return;
            }
            applyOutputDirectory(path);
        }
    }

    // ------------------------------------------------------------ results/io

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }

        if (requestCode == REQUEST_DECRYPT_TREE || requestCode == REQUEST_OUTPUT_TREE) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                usePickedFolder(requestCode, treeUri);
            }
            return;
        }

        if (requestCode != REQUEST_IMPORT_DB || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        try {
            String json = readText(uri);
            long count = importAny(json);
            saveDatabase(json);
            refreshList();
            toast("Loaded " + count + " titles");
        } catch (Exception error) {
            toast("Import failed: " + error.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE) {
            retryPendingPath();
        }
    }

    private String readText(Uri uri) throws Exception {
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) {
            throw new IllegalStateException("cannot open the selected file");
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString("UTF-8");
        } finally {
            input.close();
        }
    }

    private String readText(File file) throws Exception {
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream((int) Math.max(1024L, file.length()));
            byte[] chunk = new byte[64 * 1024];
            int read;
            while ((read = input.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString("UTF-8");
        } finally {
            input.close();
        }
    }

    // --------------------------------------------------------------- widgets

    private TextView pill(String label, int backgroundRes, int textRes) {
        TextView view = text(label, 14, textRes);
        view.setTypeface(medium());
        view.setGravity(Gravity.CENTER);
        view.setBackground(pressable(color(backgroundRes), color(R.color.stroke), 16));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private TextView text(String value, float sizeSp, int colorRes) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color(colorRes));
        return view;
    }

    private Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    private int color(int colorRes) {
        return getResources().getColor(colorRes, getTheme());
    }

    private GradientDrawable fill(int fillColor, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadius(radiusDp * getResources().getDisplayMetrics().density);
        return drawable;
    }

    private GradientDrawable outline(int fillColor, int strokeColor, float radiusDp) {
        GradientDrawable drawable = fill(fillColor, radiusDp);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private Drawable pressable(int normal, int pressed, float radiusDp) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, fill(pressed, radiusDp));
        states.addState(new int[]{}, fill(normal, radiusDp));
        return states;
    }

    private ColorStateList tint(int colorRes) {
        return ColorStateList.valueOf(color(colorRes));
    }

    // --------------------------------------------------------------- helpers

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        do {
            value /= 1024d;
            unit++;
        } while (value >= 1024d && unit < units.length - 1);
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
