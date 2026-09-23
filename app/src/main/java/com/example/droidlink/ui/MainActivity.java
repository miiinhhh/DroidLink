package com.example.droidlink.ui;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.os.Build;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.droidlink.R;
import com.example.droidlink.network.DeviceDiscovery;
import com.example.droidlink.service.ScreenCaptureService;
import com.example.droidlink.service.StreamingServer;

public class MainActivity extends AppCompatActivity {

    private Button btnStartMirroring;
    private Button btnStopMirroring;
    private TextView tvStatus;

    private MediaProjectionManager mediaProjectionManager;
    private DeviceDiscovery deviceDiscovery;

    private final ActivityResultLauncher<Intent> screenCaptureLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {

                        if (result.getResultCode() == Activity.RESULT_OK
                                && result.getData() != null) {

                            tvStatus.setText("● Mirroring");

                            startScreenCaptureService(
                                    result.getData()
                            );

                            btnStartMirroring.setEnabled(false);
                            btnStopMirroring.setEnabled(true);

                        } else {

                            tvStatus.setText("● Permission denied (Waiting for client...)");

                            btnStartMirroring.setEnabled(true);
                            btnStopMirroring.setEnabled(false);
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStartMirroring = findViewById(R.id.btnStartMirroring);
        btnStopMirroring = findViewById(R.id.btnStopMirroring);
        tvStatus = findViewById(R.id.tvStatus);

        mediaProjectionManager =
                (MediaProjectionManager)
                        getSystemService(MEDIA_PROJECTION_SERVICE);

        btnStartMirroring.setOnClickListener(v ->
                checkAudioPermissionAndRequest()
        );

        btnStopMirroring.setOnClickListener(v ->
                stopScreenCaptureService()
        );

        // Start discovery and streaming server on app launch (waiting for client connection)
        tvStatus.setText("● Waiting for client connection...");

        deviceDiscovery = new DeviceDiscovery();
        deviceDiscovery.start();

        StreamingServer streamingServer = StreamingServer.getInstance();
        streamingServer.start();
        streamingServer.setOnClientConnectedListener(() ->
                runOnUiThread(() -> {
                    tvStatus.setText("● Client connected, requesting permission...");
                    checkAudioPermissionAndRequest();
                })
        );
    }

    private final ActivityResultLauncher<String> audioPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    isGranted -> {
                        requestScreenCapturePermission();
                    }
            );

    private void checkAudioPermissionAndRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO);
                return;
            }
        }
        requestScreenCapturePermission();
    }

    private void requestScreenCapturePermission() {
        Intent captureIntent =
                mediaProjectionManager.createScreenCaptureIntent();

        screenCaptureLauncher.launch(captureIntent);
    }

    private void startScreenCaptureService(Intent permissionData) {
        Intent serviceIntent =
                new Intent(
                        this,
                        ScreenCaptureService.class
                );

        serviceIntent.putExtra(
                "resultCode",
                Activity.RESULT_OK
        );

        serviceIntent.putExtra(
                "data",
                permissionData
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void stopScreenCaptureService() {
        Intent serviceIntent =
                new Intent(
                        this,
                        ScreenCaptureService.class
                );

        stopService(serviceIntent);

        // Restart streaming server to listen for new client connections
        StreamingServer streamingServer = StreamingServer.getInstance();
        streamingServer.start();
        streamingServer.setOnClientConnectedListener(() ->
                runOnUiThread(() -> {
                    tvStatus.setText("● Client connected, requesting permission...");
                    requestScreenCapturePermission();
                })
        );

        tvStatus.setText("● Waiting for client connection...");

        btnStartMirroring.setEnabled(true);
        btnStopMirroring.setEnabled(false);
    }

    private final android.content.BroadcastReceiver stopUiReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            tvStatus.setText("● Waiting for client connection...");
            btnStartMirroring.setEnabled(true);
            btnStopMirroring.setEnabled(false);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        android.content.IntentFilter filter = new android.content.IntentFilter("com.example.droidlink.ACTION_STOP_UI");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopUiReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stopUiReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(stopUiReceiver);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        if (deviceDiscovery != null) {
            deviceDiscovery.stop();
        }
        StreamingServer.getInstance().stop();
        super.onDestroy();
    }
}
