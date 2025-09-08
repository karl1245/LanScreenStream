package com.example.lanscreenstream;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_NOTIF = 1001;

    public static final String EXTRA_TARGET_HEIGHT = "extra_target_height"; // 480 / 720 / 1080
    public static final String EXTRA_JPEG_QUALITY = "extra_jpeg_quality";   // optional (0..100)

    private MediaProjectionManager mpManager;
    private int resultCode;
    private Intent resultData;

    private TextView tvStatus, tvUrl;
    private Button btnStart, btnStop;
    private RadioGroup rgQuality;
    private RadioButton rb480, rb720, rb1080;

    private final ActivityResultLauncher<Intent> screenCaptureLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    resultCode = result.getResultCode();
                    resultData = result.getData();
                    startStreamService();
                } else {
                    tvStatus.setText(getString(R.string.request_permission));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvUrl = findViewById(R.id.tvUrl);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        rgQuality = findViewById(R.id.rgQuality);
        rb480 = findViewById(R.id.rb480);
        rb720 = findViewById(R.id.rb720);
        rb1080 = findViewById(R.id.rb1080);

        mpManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        btnStart.setOnClickListener(v -> checkAndStart());
        btnStop.setOnClickListener(v -> stopStreamService());

        updateUrlDisplay();
    }

    private void checkAndStart() {
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

    private int getSelectedTargetHeight() {
        int checkedId = rgQuality.getCheckedRadioButtonId();
        if (checkedId == R.id.rb480) return 480;
        if (checkedId == R.id.rb1080) return 1080;
        return 720; // default
    }

    private void startStreamService() {
        String ip = NetworkUtils.getLocalIpAddress(this);
        String url = "http://" + ip + ":8080/";
        tvUrl.setText(url);
        tvStatus.setText(getString(R.string.status_running, url));

        Intent svc = new Intent(this, StreamService.class);
        svc.putExtra("resultCode", resultCode);
        svc.putExtra("data", resultData);

        // pass chosen quality; you can also pass JPEG quality if you want (e.g. 60..85)
        svc.putExtra(EXTRA_TARGET_HEIGHT, getSelectedTargetHeight());
        svc.putExtra(EXTRA_JPEG_QUALITY, 70);

        ContextCompat.startForegroundService(this, svc);
    }

    private void stopStreamService() {
        stopService(new Intent(this, StreamService.class));
        tvStatus.setText(getString(R.string.status_stopped));
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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            checkAndStart();
        }
    }
}
