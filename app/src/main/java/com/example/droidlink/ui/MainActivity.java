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
import com.example.droidlink.service.ScreenCaptureService;

public class MainActivity extends AppCompatActivity {

    private Button btnStartMirroring;
    private TextView tvStatus;

    private MediaProjectionManager mediaProjectionManager;

    private final ActivityResultLauncher<Intent> screenCaptureLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {

                        if (result.getResultCode() == Activity.RESULT_OK
                                && result.getData() != null) {

                            tvStatus.setText("● Permission granted");

                            startScreenCaptureService(
                                    result.getData()
                            );

                        } else {

                            tvStatus.setText("● Permission denied");
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStartMirroring = findViewById(R.id.btnStartMirroring);
        tvStatus = findViewById(R.id.tvStatus);

        mediaProjectionManager =
                (MediaProjectionManager)
                        getSystemService(MEDIA_PROJECTION_SERVICE);

        btnStartMirroring.setOnClickListener(v ->
                requestScreenCapturePermission()
        );
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
}