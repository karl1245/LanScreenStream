package com.example.lanscreenstream;

import static android.graphics.PixelFormat.RGBA_8888;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
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

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

public class StreamService extends Service implements MjpegHttpServer.FrameProvider {

    public static final String ACTION_STREAM_STARTED = "com.example.lanscreenstream.STREAM_STARTED";
    public static final String ACTION_STREAM_STOPPED = "com.example.lanscreenstream.STREAM_STOPPED";

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_HEIGHT      = "target_height";
    public static final String EXTRA_URL         = "url";

    private static final String TAG = "StreamService";
    private static final int JPEG_QUALITY = 70;
    private static final int PORT = 8080;

    private HandlerThread worker;
    private Handler handler;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private SurfaceTexture surfaceTexture;

    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<>(null);
    private MjpegHttpServer httpServer;

    @Override public void onCreate() {
        super.onCreate();
        NotificationHelper.ensureChannel(this);
        // Must go foreground ASAP on Android 14/15
        Notification n = new NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.presence_online)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text_waiting))
                .setOngoing(true)
                .build();
        startForeground(1, n);

        worker = new HandlerThread("stream-worker");
        worker.start();
        handler = new Handler(worker.getLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        final int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        final Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        final int targetHeight = intent.getIntExtra(EXTRA_HEIGHT, 1080);

        handler.post(() -> startProjection(resultCode, data, targetHeight));
        return START_STICKY;
    }

    private void startProjection(int resultCode, Intent data, int targetHeight) {
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = mpm.getMediaProjection(resultCode, data);

            // Use the current display metrics for width/height; scale by height selector
            int displayWidth = getResources().getDisplayMetrics().widthPixels;
            int displayHeight = getResources().getDisplayMetrics().heightPixels;
            int width = (int) Math.round((double) displayWidth * (double) targetHeight / (double) displayHeight);
            int height = targetHeight;

            imageReader = ImageReader.newInstance(width, height, RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Image img = null;
                try {
                    img = reader.acquireLatestImage();
                    if (img == null) return;
                    byte[] jpeg = ImageUtil.imageToJpeg(img, JPEG_QUALITY);
                    latestJpeg.set(jpeg);
                } catch (Throwable ignored) {
                } finally {
                    if (img != null) img.close();
                }
            }, handler);

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "LANStreamVD",
                    width, height, getResources().getDisplayMetrics().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, handler
            );

            // Start HTTP server
            httpServer = new MjpegHttpServer(PORT, this);
            httpServer.start();

            String ip = NetworkUtils.getLocalIp(this);
            final String url = "http://" + ip + ":" + PORT + "/stream";

            // Update the foreground notification with the URL
            Notification updated = NotificationHelper.buildForeground(this, url);
            startForeground(1, updated);

            // Tell UI we're up
            sendBroadcast(new Intent(ACTION_STREAM_STARTED).putExtra(EXTRA_URL, url));

        } catch (Throwable t) {
            stopSelf();
        }
    }

    @Override public void onDestroy() {
        super.onDestroy();
        try {
            if (httpServer != null) { httpServer.stopServer(); httpServer = null; }
            if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
            if (imageReader != null) { imageReader.close(); imageReader = null; }
            if (mediaProjection != null) { mediaProjection.stop(); mediaProjection = null; }
        } catch (Throwable ignored) {}
        if (worker != null) {
            worker.quitSafely();
            worker = null;
        }
        sendBroadcast(new Intent(ACTION_STREAM_STOPPED));
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public byte[] getLatestJpeg() {
        return latestJpeg.get();
    }
}
