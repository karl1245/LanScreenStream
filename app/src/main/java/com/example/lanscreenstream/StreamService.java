package com.example.lanscreenstream;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class StreamService extends Service implements MjpegHttpServer.FrameProvider {
    public static final String TAG = "StreamService";

    public static final String ACTION_STREAM_STARTED = "com.example.lanscreenstream.STREAM_STARTED";
    public static final String ACTION_STREAM_STOPPED = "com.example.lanscreenstream.STREAM_STOPPED";
    public static final String ACTION_STREAM_ERROR   = "com.example.lanscreenstream.STREAM_ERROR";

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_HEIGHT      = "target_height";
    public static final String EXTRA_URL         = "url";
    public static final String EXTRA_ERROR       = "error";

    private static final int JPEG_QUALITY = 70;
    private static final int PORT = 8080;

    private HandlerThread worker;
    private Handler handler;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private MjpegHttpServer httpServer;

    private volatile byte[] latestJpeg;

    private final MediaProjection.Callback mpCallback = new MediaProjection.Callback() {
        @Override public void onStop() {
            Log.w(TAG, "MediaProjection onStop() — capture ended by system/user");
            sendError("Capture permission revoked or projection stopped");
            stopSelf();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate(): preparing notification + worker");

        // Quick self-checks for the common pitfalls:
        int p = checkSelfPermission("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION");
        Log.i(TAG, "Has FOREGROUND_SERVICE_MEDIA_PROJECTION perm? " + (p == PackageManager.PERMISSION_GRANTED));
        // NOTE: This permission is install-time; if false, the manifest placement is wrong.

        NotificationHelper.ensureChannel(this);
        Notification n = new NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.presence_online)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text_waiting))
                .setOngoing(true)
                .build();
        try {
            Log.i(TAG, "Calling startForeground() in onCreate()");
            startForeground(1, n);  // must be immediate on Android 14/15
        } catch (Throwable t) {
            Log.e(TAG, "startForeground() failed – " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
            sendError("startForeground failed: " + t.getMessage());
            stopSelf();
            return;
        }

        worker = new HandlerThread("stream-worker");
        worker.start();
        handler = new Handler(worker.getLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand(): flags=" + flags + " startId=" + startId + " intent=" + intent);
        if (intent == null) { sendError("Null intent"); stopSelf(); return START_NOT_STICKY; }

        final int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        final Intent data    = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        final int targetH    = intent.getIntExtra(EXTRA_HEIGHT, 1080);
        Log.i(TAG, "Extras: resultCode=" + resultCode + " data=" + (data!=null) + " targetHeight=" + targetH);

        handler.post(() -> setupProjection(resultCode, data, targetH));
        return START_STICKY;
    }

    private void setupProjection(int resultCode, Intent data, int targetHeight) {
        Log.d(TAG, "setupProjection(): start");
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mpm == null) { sendError("MediaProjectionManager not available"); stopSelf(); return; }

            mediaProjection = mpm.getMediaProjection(resultCode, data);
            if (mediaProjection == null) { sendError("MediaProjection is null (user denied?)"); stopSelf(); return; }

            // >>> This must come BEFORE createVirtualDisplay (Android 14/15)
            mediaProjection.registerCallback(mpCallback, handler);
            Log.i(TAG, "MediaProjection callback registered");

            int dw = getResources().getDisplayMetrics().widthPixels;
            int dh = getResources().getDisplayMetrics().heightPixels;
            int width  = Math.max(1, (int)Math.round((double)dw * targetHeight / (double)dh));
            int height = Math.max(1, targetHeight);
            int dpi    = getResources().getDisplayMetrics().densityDpi;
            Log.i(TAG, "VD size " + width + "x" + height + " @" + dpi + "dpi");

            int chosenFormat = (Build.VERSION.SDK_INT >= 29) ? PixelFormat.RGBA_8888 : ImageFormat.YUV_420_888;
            try {
                imageReader = ImageReader.newInstance(width, height, chosenFormat, 2);
                Log.i(TAG, "ImageReader created format=" + chosenFormat);
            } catch (Throwable e) {
                Log.w(TAG, "RGBA ImageReader failed; falling back to YUV_420_888", e);
                chosenFormat = ImageFormat.YUV_420_888;
                imageReader = ImageReader.newInstance(width, height, chosenFormat, 2);
            }

            imageReader.setOnImageAvailableListener(reader -> {
                Image img = null;
                try {
                    img = reader.acquireLatestImage();
                    if (img == null) return;
                    latestJpeg = ImageUtil.imageToJpeg(img, JPEG_QUALITY);
                } catch (Throwable t) {
                    Log.e(TAG, "Frame conversion error", t);
                } finally {
                    if (img != null) img.close();
                }
            }, handler);

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "LANStreamVD", width, height, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, handler
            );
            Log.i(TAG, "VirtualDisplay created");

            // HTTP server
            httpServer = new MjpegHttpServer(PORT, this);
            httpServer.start();
            Log.i(TAG, "HTTP server started on port " + PORT);

            String ip  = NetworkUtils.getLocalIp(this);
            String url = "http://" + ip + ":" + PORT + "/stream";
            Log.i(TAG, "Serving at " + url);

            // Update notification with URL (safe to call again)
            Notification updated = NotificationHelper.buildForeground(this, url);
            startForeground(1, updated);

            sendBroadcast(new Intent(ACTION_STREAM_STARTED).putExtra(EXTRA_URL, url));
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException during setupProjection", se);
            sendError("SecurityException: " + se.getMessage());
            stopSelf();
        } catch (Throwable t) {
            Log.e(TAG, "setupProjection failed", t);
            sendError("setupProjection failed: " + t.getMessage());
            stopSelf();
        }
    }

    private void sendError(String message) {
        Log.e(TAG, "sendError: " + message);
        sendBroadcast(new Intent(ACTION_STREAM_ERROR).putExtra(EXTRA_ERROR, message));
    }

    @Override public void onDestroy() {
        Log.d(TAG, "onDestroy(): cleaning up");
        try {
            if (httpServer != null) { httpServer.stopServer(); Log.i(TAG, "HTTP server stopped"); httpServer = null; }
            if (virtualDisplay != null) { virtualDisplay.release(); Log.i(TAG, "VirtualDisplay released"); virtualDisplay = null; }
            if (imageReader != null) { imageReader.close(); Log.i(TAG, "ImageReader closed"); imageReader = null; }
            if (mediaProjection != null) { mediaProjection.unregisterCallback(mpCallback); mediaProjection.stop(); Log.i(TAG, "MediaProjection stopped"); mediaProjection = null; }
        } catch (Throwable t) {
            Log.w(TAG, "Cleanup error", t);
        }
        sendBroadcast(new Intent(ACTION_STREAM_STOPPED));
        if (worker != null) { worker.quitSafely(); worker = null; }
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public byte[] getLatestJpeg() { return latestJpeg; }
}
