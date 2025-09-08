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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_NOTIF = 1001;
    private MediaProjectionManager mpManager;
    private int resultCode;
    private Intent resultData;

    private TextView tvStatus, tvUrl;
    private Button btnStart, btnStop;

    private boolean isRunning = false;
    private boolean isStopping = false;

    private final ActivityResultLauncher<Intent> screenCaptureLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    resultCode = result.getResultCode();
                    resultData = result.getData();
                    startStreamService();
                } else {
                    tvStatus.setText(getString(R.string.request_permission));
                    setUiRunning(false);
                }
            });

    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (StreamService.ACTION_STREAM_STOPPED.equals(intent.getAction())) {
                isRunning = false;
                isStopping = false;
                setUiRunning(false);
                tvStatus.setText(getString(R.string.status_stopped));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvUrl    = findViewById(R.id.tvUrl);
        btnStart = findViewById(R.id.btnStart);
        btnStop  = findViewById(R.id.btnStop);

        mpManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        btnStart.setOnClickListener(v -> checkAndStart());
        btnStop.setOnClickListener(v -> stopStreamService());

        updateUrlDisplay();

        // Receive "stopped" broadcast from the service
        registerReceiver(stopReceiver, new IntentFilter(StreamService.ACTION_STREAM_STOPPED));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(stopReceiver);
        } catch (Throwable ignored) {}
    }

    private void checkAndStart() {
        if (isRunning || isStopping) return;

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                return;
            }
        }
        if (resultData == null) {
            Intent intent = mpManager.createScreenCaptureIntent();
            screenCaptureLauncher.launch(intent);
        } else {
            startStreamService();
        }
    }

    private void startStreamService() {
        int maxWidth = getSelectedMaxWidth(); // 480/720/1080 (defaults to 720 if controls absent)
        String ip = NetworkUtils.getLocalIpAddress(this);
        String url = "http://" + ip + ":8080/";
        tvUrl.setText(url);
        tvStatus.setText(getString(R.string.status_running, url));

        Intent svc = new Intent(this, StreamService.class);
        svc.putExtra("resultCode", resultCode);
        svc.putExtra("data", resultData);
        svc.putExtra(StreamService.EXTRA_MAX_WIDTH, maxWidth);
        svc.putExtra(StreamService.EXTRA_JPEG_QUALITY, 60); // tweak if needed

        ContextCompat.startForegroundService(this, svc);

        isRunning = true;
        setUiRunning(true);
    }

    private void stopStreamService() {
        if (!isRunning || isStopping) return;

        isStopping = true;
        tvStatus.setText("Stopping…");
        btnStart.setEnabled(false);
        btnStop.setEnabled(false);
        setQualitySelectorEnabled(false);

        stopService(new Intent(this, StreamService.class));
        // We’ll re-enable controls when ACTION_STREAM_STOPPED arrives
    }

    private void updateUrlDisplay() {
        String ip = NetworkUtils.getLocalIpAddress(this);
        if (ip != null) {
            String url = "http://" + ip + ":8080/";
            tvUrl.setText(url);
        } else {
            tvUrl.setText("No LAN IP found");
        }
    }

    private void setUiRunning(boolean running) {
        // Start disabled while running; Stop enabled while running
        btnStart.setEnabled(!running && !isStopping);
        btnStop.setEnabled(running && !isStopping);
        setQualitySelectorEnabled(!running && !isStopping);
    }

    // --- Quality selector helpers ---
    private int getSelectedMaxWidth() {
        // If you added RadioButtons with these ids, this will read them.
        // If not found, defaults to 720.
        RadioButton rb1080 = findViewById(R.id.rb1080);
        RadioButton rb720  = findViewById(R.id.rb720);
        RadioButton rb480  = findViewById(R.id.rb480);

        if (rb1080 != null && rb1080.isChecked()) return 1080;
        if (rb720  != null && rb720.isChecked())  return 720;
        if (rb480  != null && rb480.isChecked())  return 480;
        return 720;
    }

    private void setQualitySelectorEnabled(boolean enabled) {
        RadioButton rb1080 = findViewById(R.id.rb1080);
        RadioButton rb720  = findViewById(R.id.rb720);
        RadioButton rb480  = findViewById(R.id.rb480);

        if (rb1080 != null) rb1080.setEnabled(enabled);
        if (rb720  != null) rb720.setEnabled(enabled);
        if (rb480  != null) rb480.setEnabled(enabled);
    }
    // ---------------------------------

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            checkAndStart();
        }
    }
}
