package io.github.xpl0itu.wiiudownloader.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
 * downloading and decryption; this activity handles browsing, queueing and
 * progress, polling {@code Mobile.progress()} while a transfer runs.
 */
public final class MainActivity extends Activity {

    private static final String PREFS = "wiiudownloader";
    private static final String PREF_OUTPUT_DIR = "output_dir";
    private static final String PREF_DECRYPT = "decrypt";
    private static final String PREF_DELETE_ENCRYPTED = "delete_encrypted";
    private static final String PREF_DATABASE = "database_json";
    private static final int REQUEST_IMPORT_DB = 1001;

    private static final String[] CATEGORY_LABELS = {"All types", "Games", "Updates", "DLC", "Demos"};
    // Matches TITLE_CATEGORY_* in the Go core.
    private static final int[] CATEGORY_VALUES = {4, 0, 1, 2, 3};

    private static final String[] REGION_LABELS = {"All regions", "USA", "Europe", "Japan"};
    // Matches MCP_REGION_* masks in the Go core; 0 means "no filter".
    private static final int[] REGION_VALUES = {0, 2, 4, 1};

    private EditText searchBox;
    private Spinner categorySpinner;
    private Spinner regionSpinner;
    private ListView listView;
    private TextView statusView;
    private TextView queueSummaryView;
    private ProgressBar progressBar;
    private Button downloadButton;
    private Button cancelButton;
    private CheckBox decryptCheckBox;
    private CheckBox deleteEncryptedCheckBox;
    private TitleAdapter adapter;

    private final Map<String, Title> queue = new LinkedHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private String outputDirectory;
    private volatile boolean running;
    private volatile boolean cancelRequested;
    private boolean polling;

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
                    statusView.setText("Finished");
                } else if ("error".equals(phase) || (!error.isEmpty() && !"idle".equals(phase))) {
                    statusView.setText("Error: " + error);
                } else if ("cancelled".equals(phase)) {
                    statusView.setText("Cancelled");
                } else {
                    long downloaded = progress.optLong("downloaded", 0L);
                    long total = progress.optLong("total", 0L);
                    long speed = progress.optLong("speed", 0L);
                    StringBuilder builder = new StringBuilder();
                    builder.append(progress.optString("title", ""));
                    if (builder.length() > 0) {
                        builder.append(" \u2014 ");
                    }
                    builder.append(message.isEmpty() ? "Working..." : message);
                    if (total > 0) {
                        builder.append(String.format(Locale.US, "  %s / %s", formatBytes(downloaded), formatBytes(total)));
                    }
                    if (speed > 0) {
                        builder.append("  ").append(formatBytes(speed)).append("/s");
                    }
                    statusView.setText(builder.toString());
                }
            } catch (Exception ignored) {
                // A progress snapshot can race with the end of a run; the next
                // tick or the run's completion handler settles the UI.
            }
            handler.postDelayed(this, 300);
        }
    };

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
    }

    @Override
    protected void onDestroy() {
        polling = false;
        handler.removeCallbacks(progressPoll);
        executor.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- layout

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(12);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(0xFFFAFAFA);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("WiiUDownloader");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);

        Button menuButton = new Button(this);
        menuButton.setText("\u22ee");
        menuButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showMenu(view);
            }
        });
        header.addView(menuButton);
        root.addView(header);

        TextView versionView = new TextView(this);
        versionView.setText("core " + Mobile.version() + " \u00b7 GPLv3");
        versionView.setTextSize(11);
        versionView.setAlpha(0.6f);
        root.addView(versionView);

        searchBox = new EditText(this);
        searchBox.setHint("Search name or title ID");
        searchBox.setSingleLine(true);
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT);
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
        root.addView(searchBox);

        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);

        categorySpinner = new Spinner(this);
        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, CATEGORY_LABELS);
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        categorySpinner.setAdapter(categoryAdapter);
        categorySpinner.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        categorySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refreshList();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        filterRow.addView(categorySpinner);

        regionSpinner = new Spinner(this);
        ArrayAdapter<String> regionAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, REGION_LABELS);
        regionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        regionSpinner.setAdapter(regionAdapter);
        regionSpinner.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        regionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refreshList();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        filterRow.addView(regionSpinner);
        root.addView(filterRow);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button addButton = new Button(this);
        addButton.setText("Add ID");
        addButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showAddTitleDialog();
            }
        });
        actions.addView(addButton);

        Button folderButton = new Button(this);
        folderButton.setText("Folder");
        folderButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showFolderDialog();
            }
        });
        actions.addView(folderButton);

        Button clearButton = new Button(this);
        clearButton.setText("Clear queue");
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                queue.clear();
                updateQueueUi();
            }
        });
        actions.addView(clearButton);
        root.addView(actions);

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.HORIZONTAL);
        options.setGravity(android.view.Gravity.CENTER_VERTICAL);
        decryptCheckBox = new CheckBox(this);
        decryptCheckBox.setText("Decrypt");
        decryptCheckBox.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(PREF_DECRYPT, isChecked).apply();
            }
        });
        options.addView(decryptCheckBox);

        deleteEncryptedCheckBox = new CheckBox(this);
        deleteEncryptedCheckBox.setText("Delete encrypted after decrypt");
        deleteEncryptedCheckBox.setTextSize(12);
        deleteEncryptedCheckBox.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(PREF_DELETE_ENCRYPTED, isChecked).apply();
            }
        });
        options.addView(deleteEncryptedCheckBox);
        root.addView(options);

        listView = new ListView(this);
        listView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
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
        root.addView(listView);

        queueSummaryView = new TextView(this);
        queueSummaryView.setTextSize(12);
        queueSummaryView.setAlpha(0.7f);
        root.addView(queueSummaryView);

        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setText("Ready");
        root.addView(statusView);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        root.addView(progressBar);

        LinearLayout runRow = new LinearLayout(this);
        runRow.setOrientation(LinearLayout.HORIZONTAL);

        downloadButton = new Button(this);
        downloadButton.setText("Download queue");
        downloadButton.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        downloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startDownloadRun();
            }
        });
        runRow.addView(downloadButton);

        cancelButton = new Button(this);
        cancelButton.setText("Cancel");
        cancelButton.setEnabled(false);
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                cancelRequested = true;
                Mobile.cancel();
                statusView.setText("Cancelling...");
            }
        });
        runRow.addView(cancelButton);
        root.addView(runRow);

        setContentView(root);
        updateQueueUi();
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Import title database\u2026");
        menu.getMenu().add(0, 2, 1, "Decrypt downloaded folder\u2026");
        menu.getMenu().add(0, 3, 2, "Output folder\u2026");
        menu.getMenu().add(0, 4, 3, "About");
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        importDatabase();
                        return true;
                    case 2:
                        showDecryptFolderDialog();
                        return true;
                    case 3:
                        showFolderDialog();
                        return true;
                    case 4:
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
        int category = CATEGORY_VALUES[Math.max(0, categorySpinner.getSelectedItemPosition())];
        int region = REGION_VALUES[Math.max(0, regionSpinner.getSelectedItemPosition())];
        String query = searchBox.getText().toString();

        List<Title> titles = new ArrayList<>();
        try {
            String json = Mobile.searchTitles(query, category, region, 0);
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                titles.add(Title.fromJson(array.getJSONObject(i)));
            }
        } catch (Exception error) {
            toast("Search failed: " + error.getMessage());
        }

        adapter.setItems(titles);
        if (titles.isEmpty()) {
            if (Mobile.databaseCount() == 0) {
                statusView.setText("No title database loaded. Use Add ID for a title ID, or import a titles JSON from the menu.");
            } else {
                statusView.setText("No titles match this filter.");
            }
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
        Set<String> ids = new LinkedHashSet<>(queue.keySet());
        adapter.setQueued(ids);
        downloadButton.setText("Download queue (" + queue.size() + ")");
        downloadButton.setEnabled(!queue.isEmpty() && !running);
        queueSummaryView.setText(queue.isEmpty()
                ? "Output: " + outputDirectory
                : queue.size() + " queued \u00b7 output: " + outputDirectory);
    }

    private void loadStoredDatabase() {
        String stored = prefs.getString(PREF_DATABASE, null);
        if (stored != null && !stored.isEmpty()) {
            try {
                Mobile.importTitleDatabaseJSON(stored);
            } catch (Exception error) {
                toast("Stored database could not be loaded: " + error.getMessage());
            }
        }
    }

    // --------------------------------------------------------------- dialogs

    private void showAddTitleDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        container.setPadding(padding, padding, padding, padding);

        final EditText idBox = new EditText(this);
        idBox.setHint("0005000010101d00");
        idBox.setSingleLine(true);
        idBox.setInputType(InputType.TYPE_CLASS_TEXT);
        container.addView(idBox);

        final EditText versionBox = new EditText(this);
        versionBox.setHint("Title version (blank = latest)");
        versionBox.setSingleLine(true);
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
                .setMessage(title.subtitle() + "\n\nFetching size\u2026")
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

    private void showFolderDialog() {
        final EditText input = new EditText(this);
        input.setText(outputDirectory);
        input.setSingleLine(false);
        int padding = dp(16);
        input.setPadding(padding, padding, padding, padding);

        new AlertDialog.Builder(this)
                .setTitle("Output folder")
                .setMessage("Files are written straight to the filesystem, so use a path this app can write to (app storage always works).")
                .setView(input)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String path = input.getText().toString().trim();
                        if (path.isEmpty()) {
                            return;
                        }
                        outputDirectory = path;
                        prefs.edit().putString(PREF_OUTPUT_DIR, path).apply();
                        updateQueueUi();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDecryptFolderDialog() {
        final EditText input = new EditText(this);
        input.setHint(outputDirectory);
        int padding = dp(16);
        input.setPadding(padding, padding, padding, padding);

        new AlertDialog.Builder(this)
                .setTitle("Decrypt downloaded folder")
                .setView(input)
                .setPositiveButton("Decrypt", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final String path = input.getText().toString().trim();
                        if (path.isEmpty()) {
                            toast("Enter the folder that holds title.tmd");
                            return;
                        }
                        startDecryptFolder(path);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("WiiUDownloader for Android")
                .setMessage("Downloads Wii U titles from Nintendo's CDN and decrypts them.\n\n"
                        + "Core: " + Mobile.version() + "\n"
                        + "Titles in database: " + Mobile.databaseCount() + "\n\n"
                        + "The desktop title database feed is offline, so add titles by ID or import a titles JSON file.\n\n"
                        + "GPLv3 \u00b7 based on WiiUDownloader by Xpl0itU")
                .setPositiveButton("OK", null)
                .show();
    }

    // ------------------------------------------------------------- downloads

    private void startDownloadRun() {
        if (running || queue.isEmpty()) {
            return;
        }
        File directory = new File(outputDirectory);
        if (!directory.exists() && !directory.mkdirs()) {
            toast("Cannot create " + outputDirectory);
            return;
        }

        final boolean decrypt = decryptCheckBox.isChecked();
        final boolean deleteEncrypted = deleteEncryptedCheckBox.isChecked();
        final List<Title> batch = new ArrayList<>(queue.values());

        running = true;
        cancelRequested = false;
        downloadButton.setEnabled(false);
        cancelButton.setEnabled(true);
        progressBar.setProgress(0);
        statusView.setText("Starting...");

        polling = true;
        handler.post(progressPoll);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                int failures = 0;
                String lastError = "";
                for (int i = 0; i < batch.size(); i++) {
                    if (cancelRequested) {
                        break;
                    }
                    final Title title = batch.get(i);
                    final int position = i + 1;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusView.setText("Title " + position + "/" + batch.size() + ": " + title.displayName());
                        }
                    });
                    try {
                        Mobile.downloadTitle(title.titleId, title.version, outputDirectory, decrypt, deleteEncrypted);
                        if (!cancelRequested) {
                            queue.remove(title.titleId);
                        }
                    } catch (Exception error) {
                        failures++;
                        lastError = title.displayName() + ": " + error.getMessage();
                    }
                }

                final int totalFailures = failures;
                final String failureMessage = lastError;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        running = false;
                        polling = false;
                        handler.removeCallbacks(progressPoll);
                        cancelButton.setEnabled(false);
                        updateQueueUi();
                        if (cancelRequested) {
                            statusView.setText("Cancelled");
                        } else if (totalFailures > 0) {
                            statusView.setText("Finished with " + totalFailures + " failure(s). " + failureMessage);
                            progressBar.setProgress(0);
                        } else {
                            statusView.setText("Finished \u2014 files saved to " + outputDirectory);
                            progressBar.setProgress(100);
                        }
                    }
                });
            }
        });
    }

    private void startDecryptFolder(String path) {
        if (running) {
            toast("A transfer is already running");
            return;
        }
        final boolean deleteEncrypted = deleteEncryptedCheckBox.isChecked();

        running = true;
        cancelRequested = false;
        downloadButton.setEnabled(false);
        cancelButton.setEnabled(true);
        progressBar.setProgress(0);
        statusView.setText("Decrypting " + path + "...");

        polling = true;
        handler.post(progressPoll);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                String failure = "";
                try {
                    Mobile.decryptFolder(path, path, deleteEncrypted);
                } catch (Exception error) {
                    failure = error.getMessage() == null ? error.toString() : error.getMessage();
                }
                final String message = failure;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        running = false;
                        polling = false;
                        handler.removeCallbacks(progressPoll);
                        cancelButton.setEnabled(false);
                        updateQueueUi();
                        if (!message.isEmpty()) {
                            statusView.setText("Decryption failed: " + message);
                        } else {
                            statusView.setText("Decryption finished");
                            progressBar.setProgress(100);
                        }
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------- db import

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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_DB || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            String json = readText(uri);
            long count = Mobile.importTitleDatabaseJSON(json);
            prefs.edit().putString(PREF_DATABASE, json).apply();
            refreshList();
            toast("Loaded " + count + " titles");
        } catch (Exception error) {
            toast("Import failed: " + error.getMessage());
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
