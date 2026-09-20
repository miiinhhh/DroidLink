package com.example.droidlink.service;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {

    private static final String TAG = "DroidLink";
    private static final String CHANNEL_ID = "screen_capture";

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec mediaCodec;
    private Surface inputSurface;
    private Thread encoderThread;
    private volatile boolean isEncoding = false;
    private StreamingServer streamingServer;

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

        // Ensure width and height are even numbers (required by H.264 encoders)
        screenWidth = (metrics.widthPixels + 1) & ~1;
        screenHeight = (metrics.heightPixels + 1) & ~1;
        screenDensity = metrics.densityDpi;

        Log.d(
                TAG,
                "Screen size: "
                        + screenWidth
                        + "x"
                        + screenHeight
        );

        try {
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    screenWidth,
                    screenHeight
            );
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 2000000); // 2 Mbps
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1 second key frame interval

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = mediaCodec.createInputSurface();
            mediaCodec.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize H.264 MediaCodec encoder", e);
            stopSelf();
            return;
        }

        virtualDisplay =
                mediaProjection.createVirtualDisplay(
                        "DroidLink",
                        screenWidth,
                        screenHeight,
                        screenDensity,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        inputSurface,
                        null,
                        null
                );

        Log.d(TAG, "VirtualDisplay created with H.264 encoder");

        // Start local streaming server
        streamingServer = new StreamingServer();
        streamingServer.start();
        Log.d(TAG, "Local streaming server started at tcp://" + StreamingServer.getLocalIpAddress() + ":8080");

        isEncoding = true;
        encoderThread = new Thread(this::encodeLoop, "H264EncoderThread");
        encoderThread.start();
    }

    private void encodeLoop() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (isEncoding) {
            try {
                int outputBufferId = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);
                if (outputBufferId >= 0) {
                    ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputBufferId);
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            outputBuffer.position(bufferInfo.offset);
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                            byte[] h264Data = new byte[bufferInfo.size];
                            outputBuffer.get(h264Data);

                            // Broadcast encoded H.264 NAL packet to connected clients
                            if (streamingServer != null) {
                                streamingServer.broadcastData(h264Data);
                            }
                        }
                    }
                    mediaCodec.releaseOutputBuffer(outputBufferId, false);
                } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = mediaCodec.getOutputFormat();
                    Log.d(TAG, "Encoder format changed: " + newFormat);
                }
            } catch (Exception e) {
                if (isEncoding) {
                    Log.e(TAG, "Error in H.264 encoding loop", e);
                }
                break;
            }
        }
    }

    private void stopScreenCapture() {
        isEncoding = false;

        if (streamingServer != null) {
            streamingServer.stop();
            streamingServer = null;
        }

        if (encoderThread != null) {
            try {
                encoderThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            encoderThread = null;
        }

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
                mediaCodec.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaCodec", e);
            }
            mediaCodec = null;
        }

        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }

        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        Log.d(TAG, "Screen capture and H.264 encoding stopped");
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
