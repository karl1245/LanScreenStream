package com.example.lanscreenstream;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_NOTIF = 1001;

    private Button btnStart, btnStop;
    private RadioButton rb1080, rb720, rb480;
    private TextView tvUrl;

    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (StreamService.ACTION_STREAM_STARTED.equals(action)) {
                setControlsEnabled(false);
                String url = intent.getStringExtra(StreamService.EXTRA_URL);
                if (url != null) tvUrl.setText(url);
            } else if (StreamService.ACTION_STREAM_STOPPED.equals(action)) {
                setControlsEnabled(true);
            }
        }
    };

    private final ActivityResultLauncher<Intent> projectionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent svc = new Intent(this, StreamService.class)
                            .putExtra(StreamService.EXTRA_RESULT_CODE, result.getResultCode())
                            .putExtra(StreamService.EXTRA_RESULT_DATA, result.getData())
                            .putExtra(StreamService.EXTRA_HEIGHT, getSelectedHeight());
                    ContextCompat.startForegroundService(this, svc);
                } else {
                    // User cancelled capture permission
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStart = findViewById(R.id.btnStart);
        btnStop  = findViewById(R.id.btnStop);
        rb1080   = findViewById(R.id.rb1080);
        rb720    = findViewById(R.id.rb720);
        rb480    = findViewById(R.id.rb480);
        tvUrl    = findViewById(R.id.tvUrl);

        btnStart.setOnClickListener(v -> checkAndStart());
        btnStop.setOnClickListener(v -> stopService(new Intent(this, StreamService.class)));

        // Show a hint if notifications are blocked (Android 13+ requires runtime grant)
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            tvUrl.setText(getString(R.string.notif_perm_hint));
        }
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(StreamService.ACTION_STREAM_STARTED);
        f.addAction(StreamService.ACTION_STREAM_STOPPED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(serviceReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serviceReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        }
    }

    @Override protected void onStop() {
        super.onStop();
        try { unregisterReceiver(serviceReceiver); } catch (Throwable ignored) {}
    }

    private void checkAndStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.POST_NOTIFICATIONS }, REQ_NOTIF);
            return;
        }

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            // Offer to open settings if projection not available (shouldn't happen)
            startActivity(new Intent(Settings.ACTION_SETTINGS));
            return;
        }
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
            checkAndStart();
        }
    }
}
