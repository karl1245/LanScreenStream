package com.example.lanscreenstream;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_NOTIF = 1001;
    private static final int SERVER_PORT = 8080;

    private MediaProjectionManager mpManager;
    private int resultCode;
    private Intent resultData;

    private TextView tvStatus, tvUrl;
    private Button btnStart, btnStop;

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

        mpManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        btnStart.setOnClickListener(v -> checkAndStart());
        btnStop.setOnClickListener(v -> stopStreamService());

        updateUrlDisplay();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUrlDisplay();
    }

    private void checkAndStart() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
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
        String deviceIp = NetworkUtils.getLocalIpAddress(this);
        String deviceUrl = buildHttp(deviceIp, SERVER_PORT);

        // Show helpful text before starting, in case user wants to copy it
        tvUrl.setText(buildUiUrlText(deviceIp, SERVER_PORT));
        tvStatus.setText(getString(R.string.status_running, deviceUrl));

        Intent svc = new Intent(this, StreamService.class);
        svc.putExtra("resultCode", resultCode);
        svc.putExtra("data", resultData);
        ContextCompat.startForegroundService(this, svc);
    }

    private void stopStreamService() {
        stopService(new Intent(this, StreamService.class));
        tvStatus.setText(getString(R.string.status_stopped));
    }

    private void updateUrlDisplay() {
        String deviceIp = NetworkUtils.getLocalIpAddress(this);
        tvUrl.setText(buildUiUrlText(deviceIp, SERVER_PORT));
    }

    private String buildUiUrlText(String deviceIp, int port) {
        StringBuilder sb = new StringBuilder();

        if (!TextUtils.isEmpty(deviceIp)) {
            sb.append("Device URL: ").append(buildHttp(deviceIp, port));
        } else {
            sb.append("Device URL: No LAN IP found");
        }

        return sb.toString();
    }

    private static String buildHttp(String ip, int port) {
        if (TextUtils.isEmpty(ip)) return "N/A";
        return "http://" + ip + ":" + port + "/";
    }

    private boolean isProbablyEmulator() {
        final String fp = Build.FINGERPRINT != null ? Build.FINGERPRINT.toLowerCase() : "";
        final String model = Build.MODEL != null ? Build.MODEL.toLowerCase() : "";
        final String brand = Build.BRAND != null ? Build.BRAND.toLowerCase() : "";
        final String product = Build.PRODUCT != null ? Build.PRODUCT.toLowerCase() : "";

        return fp.contains("generic") || fp.contains("ranchu") || fp.contains("emulator")
                || model.contains("android sdk built for") || brand.contains("generic")
                || product.contains("sdk") || product.contains("emulator") || product.contains("google_sdk");
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
