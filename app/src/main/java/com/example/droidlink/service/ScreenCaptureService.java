package com.example.droidlink.service;

import android.app.Activity;
import android.app.Notification;
import android.content.pm.ServiceInfo;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;

public class ScreenCaptureService extends Service {

    private static final String TAG = "DroidLink";
    private static final String CHANNEL_ID = "screen_capture";

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();

        Notification.Builder notificationBuilder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationBuilder =
                    new Notification.Builder(this, CHANNEL_ID);
        } else {
            notificationBuilder =
                    new Notification.Builder(this);
        }

        Notification notification =
                notificationBuilder
                        .setContentTitle("DroidLink")
                        .setContentText("Screen mirroring is active")
                        .setSmallIcon(android.R.drawable.ic_menu_view)
                        .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    1,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(1, notification);
        }

        Log.d(TAG, "ScreenCaptureService created");
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId) {

        if (intent == null) {
            return START_NOT_STICKY;
        }

        int resultCode =
                intent.getIntExtra(
                        "resultCode",
                        Activity.RESULT_CANCELED
                );

        Intent data;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data = intent.getParcelableExtra("data", Intent.class);
        } else {
            data = intent.getParcelableExtra("data");
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "MediaProjection permission data is missing");
            stopSelf();
            return START_NOT_STICKY;
        }

        startScreenCapture(resultCode, data);

        return START_NOT_STICKY;
    }

    private void startScreenCapture(
            int resultCode,
            Intent data) {

        MediaProjectionManager projectionManager =
                (MediaProjectionManager)
                        getSystemService(
                                MEDIA_PROJECTION_SERVICE
                        );

        mediaProjection =
                projectionManager.getMediaProjection(
                        resultCode,
                        data
                );

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null");
            stopSelf();
            return;
        }

        // IMPORTANT:
        // Callback MUST be registered before createVirtualDisplay()
        mediaProjection.registerCallback(
                new MediaProjection.Callback() {

                    @Override
                    public void onStop() {

                        Log.d(
                                TAG,
                                "MediaProjection stopped"
                        );

                        stopScreenCapture();
                    }
                },
                null
        );

        DisplayMetrics metrics =
                getResources().getDisplayMetrics();

        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        Log.d(
                TAG,
                "Screen size: "
                        + screenWidth
                        + "x"
                        + screenHeight
        );

        imageReader =
                ImageReader.newInstance(
                        screenWidth,
                        screenHeight,
                        PixelFormat.RGBA_8888,
                        2
                );

        imageReader.setOnImageAvailableListener(
                reader -> {

                    Image image = null;

                    try {

                        image =
                                reader.acquireLatestImage();

                        if (image != null) {

                            Log.d(
                                    TAG,
                                    "Frame captured: "
                                            + image.getWidth()
                                            + "x"
                                            + image.getHeight()
                            );
                        }

                    } finally {

                        if (image != null) {
                            image.close();
                        }
                    }

                },
                null
        );

        virtualDisplay =
                mediaProjection.createVirtualDisplay(
                        "DroidLink",
                        screenWidth,
                        screenHeight,
                        screenDensity,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader.getSurface(),
                        null,
                        null
                );

        Log.d(
                TAG,
                "VirtualDisplay created"
        );
    }

    private void stopScreenCapture() {

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        Log.d(TAG, "Screen capture stopped");
    }

    @Override
    public void onDestroy() {

        stopScreenCapture();

        Log.d(
                TAG,
                "ScreenCaptureService destroyed"
        );

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Screen Mirroring",
                            NotificationManager.IMPORTANCE_LOW
                    );

            NotificationManager manager =
                    getSystemService(
                            NotificationManager.class
                    );

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}