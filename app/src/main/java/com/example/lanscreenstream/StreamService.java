package com.example.lanscreenstream;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.PixelCopy;
import android.view.Surface;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class StreamService extends Service implements MjpegHttpServer.FrameSource {

    private static final String TAG = "StreamService";

    // Tunables you can tweak
    private static final int TARGET_MAX_WIDTH   = 720; // try 540/480 if CPU-bound
    private static final int JPEG_QUALITY       = 60;  // 50–70 is a good speed/quality balance
    private static final long FRAME_INTERVAL_MS = 33;  // ~30 fps PixelCopy loop

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;

    private SurfaceTexture surfaceTexture;
    private Surface surface;

    private HandlerThread captureThread;
    private Handler captureHandler;

    private ExecutorService encodePool;
    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<>(null);

    private MjpegHttpServer server;
    private Timer testTimer;

    // Double-buffered bitmaps (A/B) for PixelCopy
    private Bitmap bmpA, bmpB;
    private volatile boolean useA = true;
    private volatile boolean copying = false;

    private int targetW, targetH;

    // Small queue to pass frames to the encoder (keep only newest)
    private ArrayBlockingQueue<Bitmap> encodeQueue;

    // Reuse one stream to cut allocs
    private final ByteArrayOutputStream jpegOut = new ByteArrayOutputStream(256 * 1024);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called");

        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent data = intent.getParcelableExtra("data");

        startHttpServer();

        String ip = NetworkUtils.getLocalIpAddress(this);
        String url = "http://" + ip + ":8080/";
        Log.d(TAG, "HTTP server started at: " + url);

        Notification notif = NotificationHelper.buildForeground(this, url);
        startForeground(1, notif);

        setupProjection(resultCode, data);

        // Fallback logger (stops automatically when real frames appear)
        startTestFramesFallback();

        return START_STICKY;
    }

    private void setupProjection(int resultCode, Intent data) {
        Log.d(TAG, "setupProjection called");

        MediaProjectionManager mpMgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = mpMgr.getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            Log.e(TAG, "mediaProjection is null!");
            return;
        }
        Log.d(TAG, "mediaProjection created");

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int srcW = metrics.widthPixels;
        int srcH = metrics.heightPixels;
        targetW = Math.min(TARGET_MAX_WIDTH, srcW);
        targetH = Math.max(1, (int) ((long) targetW * srcH / Math.max(1, srcW)));
        int dpi = metrics.densityDpi;

        Log.d(TAG, "Display size: " + srcW + "x" + srcH + " -> " + targetW + "x" + targetH + " dpi=" + dpi);

        // ---- Workaround path: VirtualDisplay -> SurfaceTexture -> PixelCopy ----
        surfaceTexture = new SurfaceTexture(0 /*dummy tex name*/);
        surfaceTexture.setDefaultBufferSize(targetW, targetH);
        surface = new Surface(surfaceTexture);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "screen",
                targetW, targetH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null, null
        );
        Log.d(TAG, "virtualDisplay created (SurfaceTexture + PixelCopy)");

        // High-priority capture thread
        captureThread = new HandlerThread("capture-thread", Process.THREAD_PRIORITY_DISPLAY);
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        // Double-buffered bitmaps for PixelCopy
        bmpA = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        bmpB = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);

        // Encoder single worker, latest-frame only
        encodeQueue = new ArrayBlockingQueue<>(1);
        encodePool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jpeg-encoder");
            t.setPriority(Thread.NORM_PRIORITY + 1);
            return t;
        });
        encodePool.execute(this::encodeLoop);

        // Start PixelCopy loop (~30 fps)
        startPixelCopyLoop(FRAME_INTERVAL_MS);
    }

    private void startPixelCopyLoop(long intervalMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.e(TAG, "PixelCopy requires API 26+. Your build is too old.");
            return;
        }
        Log.d(TAG, "Starting PixelCopy loop every " + intervalMs + "ms");

        captureHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (surface == null || !surface.isValid()) {
                        captureHandler.postDelayed(this, intervalMs);
                        return;
                    }
                    if (!copying) {
                        copying = true;
                        final Bitmap target = useA ? bmpA : bmpB;
                        useA = !useA;

                        PixelCopy.request(surface, target, result -> {
                            try {
                                if (result == PixelCopy.SUCCESS) {
                                    // Offer latest bitmap to encoder; drop older if still queued
                                    encodeQueue.poll();
                                    encodeQueue.offer(target);
                                    // Signal real frames to stop the test logger
                                    if (latestJpeg.get() != null && testTimer != null) {
                                        // no-op; timer will auto stop once JPEGs start flowing
                                    }
                                } else {
                                    Log.w(TAG, "PixelCopy failed, code=" + result);
                                }
                            } finally {
                                copying = false;
                            }
                        }, captureHandler);
                    }
                } catch (Throwable t) {
                    copying = false;
                    Log.e(TAG, "PixelCopy.request threw", t);
                } finally {
                    captureHandler.postDelayed(this, intervalMs);
                }
            }
        });
    }

    private void encodeLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Bitmap frame = encodeQueue.take(); // always latest due to poll/offer
                try {
                    jpegOut.reset();
                    frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpegOut);
                    latestJpeg.set(jpegOut.toByteArray());
                    Log.d(TAG, "JPEG frame updated (q=" + JPEG_QUALITY + ", " + latestJpeg.get().length + " bytes)");
                } catch (Throwable t) {
                    Log.e(TAG, "JPEG encode error", t);
                }
                // NOTE: do NOT recycle bmpA/bmpB; they’re reused as capture buffers
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void startHttpServer() {
        server = new MjpegHttpServer(8080, 10);
        server.setFrameSource(this);
        try {
            server.start();
            Log.d(TAG, "MjpegHttpServer started on port 8080");
        } catch (IOException e) {
            Log.e(TAG, "Failed to start MjpegHttpServer", e);
        }
    }

    // ----- Test-frame fallback logger (auto-stops once JPEGs appear) -----
    private void startTestFramesFallback() {
        Log.d(TAG, "Starting test frame logger");
        if (testTimer != null) return;
        testTimer = new Timer();
        testTimer.scheduleAtFixedRate(new TimerTask() {
            int safetyCounter = 0;
            @Override public void run() {
                byte[] cur = latestJpeg.get();
                if (cur != null && cur.length > 0) {
                    if (++safetyCounter >= 6) {
                        Log.d(TAG, "Real frames detected, stopping test logger");
                        testTimer.cancel();
                        testTimer = null;
                    }
                    return;
                }
                Log.d(TAG, "Test frame still active (no real frames yet)");
            }
        }, 0, 500);
    }
    // ---------------------------------------------------------------

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy called");
        super.onDestroy();

        if (server != null) {
            server.stop();
            Log.d(TAG, "HTTP server stopped");
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
            Log.d(TAG, "virtualDisplay released");
        }
        if (surface != null) {
            surface.release();
            surface = null;
            Log.d(TAG, "surface released");
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
            Log.d(TAG, "surfaceTexture released");
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
            Log.d(TAG, "mediaProjection stopped");
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
            Log.d(TAG, "captureThread quit");
        }
        if (encodePool != null) {
            encodePool.shutdownNow();
            encodePool = null;
            Log.d(TAG, "encodePool shutdown");
        }
        if (bmpA != null) { bmpA.recycle(); bmpA = null; }
        if (bmpB != null) { bmpB.recycle(); bmpB = null; }
        if (testTimer != null) {
            testTimer.cancel();
            testTimer = null;
            Log.d(TAG, "testTimer cancelled");
        }
    }

    @Override
    public byte[] getLatestJpeg() { return latestJpeg.get(); }
}
