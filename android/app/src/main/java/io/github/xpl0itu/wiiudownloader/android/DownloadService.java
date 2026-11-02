package io.github.xpl0itu.wiiudownloader.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import mobile.Mobile;

/**
 * Runs downloads and decryption in the foreground so a screen-off or
 * backgrounded app keeps transferring, and shows progress in the notification
 * shade.
 *
 * <p>The job loop lives here rather than in {@link MainActivity} on purpose: the
 * activity can be destroyed while a multi-gigabyte title is still downloading,
 * and the system keeps this service's process alive instead. The activity reads
 * the run state below and polls {@link Mobile#progress()} for the live numbers.
 */
public final class DownloadService extends Service {

    static final String MODE_DOWNLOAD = "download";
    static final String MODE_DECRYPT = "decrypt";

    private static final String CHANNEL_ID = "transfers";
    private static final int NOTIFICATION_ID = 1001;
    private static final String ACTION_CANCEL = "io.github.xpl0itu.wiiudownloader.android.action.CANCEL";

    private static final String EXTRA_MODE = "mode";
    private static final String EXTRA_TITLES = "titles";
    private static final String EXTRA_OUTPUT = "output";
    private static final String EXTRA_DECRYPT = "decrypt_contents";
    private static final String EXTRA_DELETE_ENCRYPTED = "delete_encrypted";
    private static final String EXTRA_FOLDER = "folder";
    private static final String EXTRA_PACK_WUA = "pack_wua";

    // Run state, read by the activity after a run finishes.
    private static volatile boolean running;
    private static volatile boolean cancelled;
    private static volatile int totalTitles;
    private static volatile int completedTitles;
    private static volatile int failures;
    private static volatile int decryptedFolders;
    private static volatile int packedArchives;
    private static volatile boolean packToWUA;
    private static volatile String lastError = "";
    private static volatile String currentTitle = "";
    private static volatile String mode = MODE_DOWNLOAD;
    private static final List<String> finishedTitleIds = Collections.synchronizedList(new ArrayList<String>());

    private ExecutorService executor;
    private Handler handler;
    private boolean workerStarted;
    private BroadcastReceiver cancelReceiver;

    // ------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        // Keep the core's temporary files inside the app sandbox; see
        // MainActivity for why this is needed on Android.
        Mobile.setTempDirectory(getCacheDir().getAbsolutePath());
        executor = Executors.newSingleThreadExecutor();
        handler = new Handler(Looper.getMainLooper());
        createChannel();

        cancelReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                requestCancel();
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_CANCEL);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cancelReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(cancelReceiver, filter);
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (cancelReceiver != null) {
            try {
                unregisterReceiver(cancelReceiver);
            } catch (Exception ignored) {
                // Already unregistered.
            }
        }
        executor.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        mode = intent.getStringExtra(EXTRA_MODE) == null ? MODE_DOWNLOAD : intent.getStringExtra(EXTRA_MODE);
        final String titles = intent.getStringExtra(EXTRA_TITLES);
        final String output = intent.getStringExtra(EXTRA_OUTPUT);
        final String folder = intent.getStringExtra(EXTRA_FOLDER);
        final boolean decryptContents = intent.getBooleanExtra(EXTRA_DECRYPT, true);
        final boolean deleteEncrypted = intent.getBooleanExtra(EXTRA_DELETE_ENCRYPTED, false);
        final boolean pack = intent.getBooleanExtra(EXTRA_PACK_WUA, false);
        packToWUA = pack;

        startForegroundCompat(buildNotification(MODE_DECRYPT.equals(mode) ? "Decrypting" : "WiiUDownloader",
                "Getting started...", -1, true, true));
        handler.post(ticker);

        if (workerStarted) {
            return START_NOT_STICKY;
        }
        workerStarted = true;

        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (MODE_DECRYPT.equals(mode)) {
                    runDecrypt(folder, deleteEncrypted, pack);
                } else {
                    runDownloads(titles, output, decryptContents, deleteEncrypted, pack);
                }
            }
        });
        return START_NOT_STICKY;
    }

    // ----------------------------------------------------------- job runners

    private void runDownloads(String titlesJson, String output, boolean decryptContents, boolean deleteEncrypted,
                              boolean pack) {
        int failureCount = 0;
        String error = "";
        boolean wasCancelled = false;

        try {
            JSONArray array = new JSONArray(titlesJson == null ? "[]" : titlesJson);
            totalTitles = array.length();
            completedTitles = 0;

            for (int i = 0; i < array.length(); i++) {
                if (cancelled) {
                    wasCancelled = true;
                    break;
                }
                JSONObject item = array.getJSONObject(i);
                final String titleId = item.optString("id", "");
                String name = item.optString("name", "");
                int version = item.optInt("version", -1);
                currentTitle = name.isEmpty() ? titleId : name;

                try {
                    Mobile.downloadTitle(titleId, version, output, decryptContents, deleteEncrypted, pack);
                    if (!cancelled) {
                        finishedTitleIds.add(titleId);
                    }
                } catch (Exception itemError) {
                    failureCount++;
                    error = currentTitle + ": " + describe(itemError);
                }
                completedTitles = i + 1;
            }
            if (cancelled) {
                wasCancelled = true;
            }
        } catch (Exception runError) {
            failureCount++;
            error = describe(runError);
        }
        finishRun(failureCount, error, wasCancelled);
    }

    private void runDecrypt(String folder, boolean deleteEncrypted, boolean pack) {
        int failureCount = 0;
        String error = "";
        boolean wasCancelled = false;
        currentTitle = folder == null ? "" : new java.io.File(folder).getName();
        totalTitles = 1;
        completedTitles = 0;

        try {
            if (folder == null || folder.trim().isEmpty()) {
                throw new IllegalArgumentException("no folder selected");
            }
            // The core accepts the title folder or a folder that contains it.
            if (pack) {
                // Decrypt, pack into a .wua and delete the working files, leaving
                // only the archive next to where the folder was.
                packedArchives = (int) Mobile.packPickedFolderToWUA(folder, true);
            } else {
                // Decrypt in place: the decrypted files replace the encrypted ones.
                decryptedFolders = (int) Mobile.decryptPickedFolder(folder, deleteEncrypted);
            }
            completedTitles = 1;
        } catch (Exception runError) {
            failureCount++;
            error = describe(runError);
        }
        if (cancelled) {
            wasCancelled = true;
        }
        finishRun(failureCount, error, wasCancelled);
    }

    private void finishRun(int failureCount, String error, boolean wasCancelled) {
        cancelled = wasCancelled || cancelled;
        failures = failureCount;
        lastError = error == null ? "" : error;
        running = false;
        currentTitle = "";

        handler.removeCallbacks(ticker);

        boolean ok = failureCount == 0 && lastError.isEmpty() && !cancelled;
        String title;
        String text;
        if (ok) {
            if (MODE_DECRYPT.equals(mode)) {
                title = packToWUA ? "Archive written" : "Decryption finished";
            } else {
                title = "Download finished";
            }
            text = "Done";
        } else if (cancelled) {
            title = "Transfer cancelled";
            text = lastError.isEmpty() ? "Cancelled" : lastError;
        } else {
            title = "Transfer finished with errors";
            text = lastError;
        }

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(title, text, ok ? 100 : -1, false, false));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(false);
        }
        stopSelf();
    }

    private static String describe(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.toString() : message;
    }

    // ---------------------------------------------------------- notification

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            try {
                JSONObject progress = new JSONObject(Mobile.progress());
                String title = progress.optString("title", currentTitle);
                String message = progress.optString("message", "");
                double percent = progress.optDouble("percent", 0d);
                long speed = progress.optLong("speed", 0L);

                StringBuilder text = new StringBuilder();
                if (!message.isEmpty()) {
                    text.append(message);
                }
                if (speed > 0) {
                    if (text.length() > 0) {
                        text.append(" · ");
                    }
                    text.append(formatSpeed(speed));
                }
                if (MODE_DOWNLOAD.equals(mode) && totalTitles > 1) {
                    text.append(" (").append(Math.min(completedTitles + 1, totalTitles)).append('/').append(totalTitles).append(')');
                }

                int value = (int) Math.round(percent * 100d);
                updateNotification(title.isEmpty() ? "WiiUDownloader" : title, text.toString(), value, false);
            } catch (Exception ignored) {
                // The snapshot can race with the end of a run.
            }
            handler.postDelayed(this, 700);
        }
    };

    private void updateNotification(String title, String text, int percent, boolean indeterminate) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        manager.notify(NOTIFICATION_ID, buildNotification(title, text, percent, indeterminate, true));
    }

    private Notification buildNotification(String title, String text, int percent, boolean indeterminate, boolean withCancel) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(running)
                .setOnlyAlertOnce(true);

        if (indeterminate) {
            builder.setProgress(0, 0, true);
        } else if (percent >= 0) {
            builder.setProgress(100, Math.max(0, Math.min(100, percent)), false);
        }

        if (withCancel) {
            Intent cancel = new Intent(ACTION_CANCEL);
            cancel.setPackage(getPackageName());
            PendingIntent cancelIntent = PendingIntent.getBroadcast(this, 1, cancel,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent).build());
        }
        return builder.build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Download and decryption progress");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private static String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond < 1024) {
            return bytesPerSecond + " B/s";
        }
        double value = bytesPerSecond;
        String[] units = {"KB/s", "MB/s", "GB/s"};
        int unit = -1;
        do {
            value /= 1024d;
            unit++;
        } while (value >= 1024d && unit < units.length - 1);
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    // ------------------------------------------------------- start / read API

    static void startDownloads(Context context, List<Title> batch, String outputDirectory,
                              boolean decryptContents, boolean deleteEncrypted, boolean pack) {
        JSONArray array = new JSONArray();
        for (Title title : batch) {
            try {
                JSONObject item = new JSONObject();
                item.put("id", title.titleId);
                item.put("name", title.displayName());
                item.put("version", title.version);
                array.put(item);
            } catch (Exception ignored) {
                // Fall through; a missing entry simply is not queued.
            }
        }
        resetState(MODE_DOWNLOAD, batch.size());
        Intent intent = new Intent(context, DownloadService.class)
                .putExtra(EXTRA_MODE, MODE_DOWNLOAD)
                .putExtra(EXTRA_TITLES, array.toString())
                .putExtra(EXTRA_OUTPUT, outputDirectory)
                .putExtra(EXTRA_DECRYPT, decryptContents)
                .putExtra(EXTRA_DELETE_ENCRYPTED, deleteEncrypted)
                .putExtra(EXTRA_PACK_WUA, pack);
        launch(context, intent);
    }

    static void startDecrypt(Context context, String folder, boolean deleteEncrypted, boolean pack) {
        resetState(MODE_DECRYPT, 1);
        Intent intent = new Intent(context, DownloadService.class)
                .putExtra(EXTRA_MODE, MODE_DECRYPT)
                .putExtra(EXTRA_FOLDER, folder)
                .putExtra(EXTRA_DELETE_ENCRYPTED, deleteEncrypted)
                .putExtra(EXTRA_PACK_WUA, pack);
        launch(context, intent);
    }

    private static void resetState(String requestedMode, int titleCount) {
        synchronized (finishedTitleIds) {
            finishedTitleIds.clear();
        }
        mode = requestedMode;
        totalTitles = titleCount;
        completedTitles = 0;
        failures = 0;
        decryptedFolders = 0;
        packedArchives = 0;
        cancelled = false;
        lastError = "";
        currentTitle = "";
        running = true;
    }

    private static void launch(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void requestCancel() {
        cancelled = true;
        Mobile.cancel();
    }

    static boolean isRunning() {
        return running;
    }

    static boolean wasCancelled() {
        return cancelled;
    }

    static int failures() {
        return failures;
    }

    static String lastError() {
        return lastError;
    }

    static int decryptedFolders() {
        return decryptedFolders;
    }

    static int packedArchives() {
        return packedArchives;
    }

    static boolean packToWUA() {
        return packToWUA;
    }

    static String lastMode() {
        return mode;
    }

    static List<String> finishedTitleIds() {
        synchronized (finishedTitleIds) {
            return new ArrayList<>(finishedTitleIds);
        }
    }
}
