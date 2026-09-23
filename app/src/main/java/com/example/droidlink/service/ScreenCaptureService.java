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
import android.os.Bundle;
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

    private byte[] sps;
    private byte[] pps;

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
            format.setInteger(MediaFormat.KEY_BIT_RATE, 4000000); // 4 Mbps for high quality and smooth motion
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1 second key frame interval

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = mediaCodec.createInputSurface();
            mediaCodec.start();

            // Request immediate sync frame (IDR) to output frames right away
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            mediaCodec.setParameters(params);
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

        // Get singleton streaming server instance
        streamingServer = StreamingServer.getInstance();
        streamingServer.setOnAllClientsDisconnectedListener(() -> {
            Log.d(TAG, "All clients disconnected, stopping screen capture");
            stopSelf();
        });

        isEncoding = true;
        encoderThread = new Thread(this::encodeLoop, "H264EncoderThread");
        encoderThread.start();
    }

    private void encodeLoop() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (isEncoding) {
            try {
                int outputBufferId =
                        mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);

                if (outputBufferId >= 0) {

                    ByteBuffer outputBuffer =
                            mediaCodec.getOutputBuffer(outputBufferId);

                    if (outputBuffer != null && bufferInfo.size > 0) {

                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(
                                bufferInfo.offset + bufferInfo.size
                        );

                        byte[] data = new byte[bufferInfo.size];
                        outputBuffer.get(data);

                        if ((bufferInfo.flags
                                & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {

                            Log.d(
                                    TAG,
                                    "H.264 codec config received, size="
                                            + data.length
                            );

                        } else {

                            boolean isIdr = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                            if (streamingServer != null) {
                                streamingServer.broadcastData(data, isIdr);
                            }

                            if (isIdr) {
                                Log.d(TAG, "IDR Key Frame broadcasted, size=" + data.length);
                            }
                        }
                    }

                    mediaCodec.releaseOutputBuffer(
                            outputBufferId,
                            false
                    );

                } else if (
                        outputBufferId
                                == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                ) {

                    MediaFormat newFormat =
                            mediaCodec.getOutputFormat();

                    Log.d(
                            TAG,
                            "Encoder format changed: " + newFormat
                    );

                    ByteBuffer csd0 =
                            newFormat.getByteBuffer("csd-0");

                    if (csd0 != null) {

                        ByteBuffer spsBuffer =
                                csd0.duplicate();

                        sps = new byte[spsBuffer.remaining()];
                        spsBuffer.get(sps);

                        Log.d(
                                TAG,
                                "SPS stored, size=" + sps.length
                        );
                    }

                    ByteBuffer csd1 =
                            newFormat.getByteBuffer("csd-1");

                    if (csd1 != null) {

                        ByteBuffer ppsBuffer =
                                csd1.duplicate();

                        pps = new byte[ppsBuffer.remaining()];
                        ppsBuffer.get(pps);

                        Log.d(
                                TAG,
                                "PPS stored, size=" + pps.length
                        );
                    }

                    if (
                            streamingServer != null
                                    && sps != null
                                    && pps != null
                    ) {

                        streamingServer.setCodecConfig(
                                sps,
                                pps
                        );

                        Log.d(
                                TAG,
                                "SPS/PPS sent to StreamingServer"
                        );
                    }
                }

            } catch (Exception e) {

                if (isEncoding) {
                    Log.e(
                            TAG,
                            "Error in H.264 encoding loop",
                            e
                    );
                }

                break;
            }
        }
    }

    private void stopScreenCapture() {
        isEncoding = false;

        // Broadcast to update UI in MainActivity
        Intent intent = new Intent("com.example.droidlink.ACTION_STOP_UI");
        intent.setPackage(getPackageName());
        sendBroadcast(intent);

        // Stop streaming server and close client sockets -> sends EOF to receiver.py -> closes ffplay
        if (streamingServer != null) {
            streamingServer.stop();
            streamingServer = null;
        }

        // Restart streaming server so it's ready to listen for new client connections
        StreamingServer.getInstance().start();

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
