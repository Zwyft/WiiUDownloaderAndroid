package io.github.xpl0itu.wiiudownloader.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
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
                        builder.append(" — ");
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

            if (DownloadService.isRunning()) {
                handler.postDelayed(this, 300);
            } else {
                settleRun();
            }
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
        menuButton.setText("⋮");
        menuButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showMenu(view);
            }
        });
        header.addView(menuButton);
        root.addView(header);

        TextView versionView = new TextView(this);
        versionView.setText("core " + Mobile.version() + " · GPLv3");
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

        Button titlesButton = new Button(this);
        titlesButton.setText("Title list");
        titlesButton.setTextSize(12);
        titlesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                importCatalog();
            }
        });
        actions.addView(titlesButton);

        Button addButton = new Button(this);
        addButton.setText("Add ID");
        addButton.setTextSize(12);
        addButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showAddTitleDialog();
            }
        });
        actions.addView(addButton);

        Button folderButton = new Button(this);
        folderButton.setText("Folder");
        folderButton.setTextSize(12);
        folderButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                pickFolder(REQUEST_OUTPUT_TREE);
            }
        });
        actions.addView(folderButton);

        Button clearButton = new Button(this);
        clearButton.setText("Clear");
        clearButton.setTextSize(12);
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
                DownloadService.requestCancel();
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
        int category = CATEGORY_VALUES[Math.max(0, categorySpinner.getSelectedItemPosition())];
        int region = REGION_VALUES[Math.max(0, regionSpinner.getSelectedItemPosition())];
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
        if (titles.isEmpty()) {
            if (Mobile.databaseCount() == 0) {
                statusView.setText("No title list loaded yet. Tap \"Title list\" to download it, or add one title by ID.");
            } else {
                statusView.setText("No titles match this filter.");
            }
        } else if (titles.size() >= RESULT_LIMIT) {
            statusView.setText("Showing the first " + RESULT_LIMIT + " matches — type more of the name to narrow it down.");
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
        Set<String> ids = new LinkedHashSet<>(queue.keySet());
        adapter.setQueued(ids);
        downloadButton.setText("Download queue (" + queue.size() + ")");
        downloadButton.setEnabled(!busy && !queue.isEmpty());
        cancelButton.setEnabled(busy);
        queueSummaryView.setText(queue.isEmpty()
                ? "Output: " + outputDirectory
                : queue.size() + " queued · output: " + outputDirectory);
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
                            statusView.setText("Stored title list could not be loaded: " + message);
                        } else if (loaded > 0) {
                            statusView.setText("Loaded " + loaded + " titles from the stored list.");
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
        final EditText input = new EditText(this);
        input.setText(prefs.getString(PREF_CATALOG_URL, CATALOG_URL));
        input.setSingleLine(false);
        int padding = dp(16);
        input.setPadding(padding, padding, padding, padding);

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
        statusView.setText("Downloading title list…");
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
                            statusView.setText("Title list download failed: " + message);
                            toast("Title list download failed: " + message);
                            return;
                        }
                        refreshList();
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
        statusView.setText("Starting...");
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
        statusView.setText("Decrypting " + path + "…");
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
            statusView.setText("Cancelled");
            progressBar.setProgress(0);
        } else if (failures > 0 || !error.isEmpty()) {
            statusView.setText("Finished with " + Math.max(failures, 1) + " failure(s). " + error);
            progressBar.setProgress(0);
        } else if (decryptRun) {
            statusView.setText("Decryption finished");
            progressBar.setProgress(100);
        } else {
            statusView.setText("Finished — files saved to " + outputDirectory);
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
