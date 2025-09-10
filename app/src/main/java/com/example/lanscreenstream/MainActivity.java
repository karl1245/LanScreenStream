package com.example.lanscreenstream;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.TextView;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQ_NOTIF = 1001;

    private Button btnStart, btnStop;
    private RadioButton rb1080, rb720, rb480;
    private TextView tvUrl;

    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "Broadcast received: " + action);
            if (StreamService.ACTION_STREAM_STARTED.equals(action)) {
                setControlsEnabled(false);
                String url = intent.getStringExtra(StreamService.EXTRA_URL);
                Log.i(TAG, "Stream started. URL=" + url);
                if (url != null) tvUrl.setText(url);
            } else if (StreamService.ACTION_STREAM_STOPPED.equals(action)) {
                Log.i(TAG, "Stream stopped");
                setControlsEnabled(true);
            } else if (StreamService.ACTION_STREAM_ERROR.equals(action)) {
                String msg = intent.getStringExtra(StreamService.EXTRA_ERROR);
                Log.e(TAG, "Stream error: " + msg);
                tvUrl.setText("Error: " + msg);
                setControlsEnabled(true);
            }
        }
    };

    private final ActivityResultLauncher<Intent> projectionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                Log.d(TAG, "MediaProjection resultCode=" + result.getResultCode() + ", data=" + (result.getData()!=null));
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent svc = new Intent(this, StreamService.class)
                            .putExtra(StreamService.EXTRA_RESULT_CODE, result.getResultCode())
                            .putExtra(StreamService.EXTRA_RESULT_DATA, result.getData())
                            .putExtra(StreamService.EXTRA_HEIGHT, getSelectedHeight());
                    Log.i(TAG, "Starting StreamService (FGS) with targetHeight=" + getSelectedHeight());
                    ContextCompat.startForegroundService(this, svc);
                } else {
                    Log.w(TAG, "User cancelled screen capture permission.");
                    tvUrl.setText("Screen capture permission was cancelled.");
                }
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate()");
        setContentView(R.layout.activity_main);

        btnStart = findViewById(R.id.btnStart);
        btnStop  = findViewById(R.id.btnStop);
        rb1080   = findViewById(R.id.rb1080);
        rb720    = findViewById(R.id.rb720);
        rb480    = findViewById(R.id.rb480);
        tvUrl    = findViewById(R.id.tvUrl);

        btnStart.setOnClickListener(v -> checkAndStart());
        btnStop.setOnClickListener(v -> {
            Log.i(TAG, "Stop pressed; stopping StreamService");
            stopService(new Intent(this, StreamService.class));
        });

        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            tvUrl.setText(getString(R.string.notif_perm_hint));
            Log.w(TAG, "POST_NOTIFICATIONS not granted yet; prompting user when Start is pressed.");
        }
    }

    @Override protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart(): registering receiver");
        IntentFilter f = new IntentFilter();
        f.addAction(StreamService.ACTION_STREAM_STARTED);
        f.addAction(StreamService.ACTION_STREAM_STOPPED);
        f.addAction(StreamService.ACTION_STREAM_ERROR);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(serviceReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serviceReceiver, f);
        }
    }

    @Override protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop(): unregistering receiver");
        try { unregisterReceiver(serviceReceiver); } catch (Throwable t) {
            Log.w(TAG, "unregisterReceiver failed", t);
        }
    }

    private void checkAndStart() {
        Log.d(TAG, "checkAndStart()");
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Requesting POST_NOTIFICATIONS");
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.POST_NOTIFICATIONS }, REQ_NOTIF);
            return;
        }

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            Log.e(TAG, "MediaProjectionManager is null");
            tvUrl.setText("MediaProjection not available on this device.");
            return;
        }
        Log.i(TAG, "Launching MediaProjection permission dialog");
        projectionLauncher.launch(mpm.createScreenCaptureIntent());
    }

    private int getSelectedHeight() {
        if (rb480 != null && rb480.isChecked()) return 480;
        if (rb720 != null && rb720.isChecked()) return 720;
        return 1080;
    }

    private void setControlsEnabled(boolean enabled) {
        if (btnStart != null) btnStart.setEnabled(enabled);
        if (rb1080 != null)  rb1080.setEnabled(enabled);
        if (rb720 != null)   rb720.setEnabled(enabled);
        if (rb480 != null)   rb480.setEnabled(enabled);
        if (btnStop != null) btnStop.setEnabled(!enabled);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            Log.d(TAG, "POST_NOTIFICATIONS onRequestPermissionsResult=" +
                    (grantResults.length>0 && grantResults[0]==PackageManager.PERMISSION_GRANTED));
            checkAndStart();
        }
    }
}
