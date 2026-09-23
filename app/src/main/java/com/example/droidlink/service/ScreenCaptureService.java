package com.example.droidlink.service;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
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

    // Audio capture and encoding fields
    private AudioRecord audioRecord;
    private MediaCodec audioEncoder;
    private Thread audioThread;
    private volatile boolean isAudioEncoding = false;
    private AudioStreamingServer audioStreamingServer;

    private byte[] sps;
    private byte[] pps;

    private long streamStartTimeMs = 0;

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
                        .setContentText("Screen mirroring & audio is active")
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

    @SuppressLint("MissingPermission")
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

        streamStartTimeMs = System.currentTimeMillis();

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

        // Step 1: Start Audio Capture and AAC Encoding (Android 10+ / API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();

                AudioFormat audioFormat = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build();

                int minBufferSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);

                audioRecord = new AudioRecord.Builder()
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(Math.max(minBufferSize, 4096 * 2))
                        .setAudioPlaybackCaptureConfig(config)
                        .build();

                MediaFormat audioFormatEnc = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2);
                audioFormatEnc.setInteger(MediaFormat.KEY_BIT_RATE, 96000); // 96 kbps
                audioFormatEnc.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                audioFormatEnc.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

                audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                audioEncoder.configure(audioFormatEnc, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                audioEncoder.start();

                audioStreamingServer = AudioStreamingServer.getInstance();
                audioStreamingServer.start();

                isAudioEncoding = true;
                audioThread = new Thread(this::audioCaptureLoop, "AudioEncoderThread");
                audioThread.start();
                Log.d(TAG, "Audio capture and AAC encoding started");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start audio capture/encoding", e);
            }
        }
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

                            long timestampMs = bufferInfo.presentationTimeUs > 0
                                    ? (bufferInfo.presentationTimeUs / 1000)
                                    : (System.currentTimeMillis() - streamStartTimeMs);

                            if (streamingServer != null) {
                                streamingServer.broadcastData(data, isIdr, timestampMs);
                            }

                            if (isIdr) {
                                Log.d(TAG, "IDR Key Frame broadcasted, size=" + data.length + ", ts=" + timestampMs);
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

    private void audioCaptureLoop() {
        if (audioRecord == null || audioEncoder == null) return;

        try {
            audioRecord.startRecording();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AudioRecord", e);
            return;
        }

        byte[] buffer = new byte[4096];
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (isAudioEncoding) {
            try {
                int readBytes = audioRecord.read(buffer, 0, buffer.length);
                if (readBytes > 0) {
                    int inputBufferId = audioEncoder.dequeueInputBuffer(10000);
                    if (inputBufferId >= 0) {
                        ByteBuffer inputBuffer = audioEncoder.getInputBuffer(inputBufferId);
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            inputBuffer.put(buffer, 0, readBytes);
                            audioEncoder.queueInputBuffer(inputBufferId, 0, readBytes, System.nanoTime() / 1000, 0);
                        }
                    }
                }

                int outputBufferId = audioEncoder.dequeueOutputBuffer(bufferInfo, 0);
                while (outputBufferId >= 0) {
                    ByteBuffer outputBuffer = audioEncoder.getOutputBuffer(outputBufferId);
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                        byte[] rawAac = new byte[bufferInfo.size];
                        outputBuffer.get(rawAac);

                        byte[] adtsPacket = addAdtsPacket(rawAac, rawAac.length);

                        long timestampMs = bufferInfo.presentationTimeUs > 0
                                ? (bufferInfo.presentationTimeUs / 1000)
                                : (System.currentTimeMillis() - streamStartTimeMs);

                        if (audioStreamingServer != null) {
                            audioStreamingServer.broadcastData(adtsPacket, timestampMs);
                        }
                    }
                    audioEncoder.releaseOutputBuffer(outputBufferId, false);
                    outputBufferId = audioEncoder.dequeueOutputBuffer(bufferInfo, 0);
                }
            } catch (Exception e) {
                if (isAudioEncoding) {
                    Log.e(TAG, "Error in audio capture/encoding loop", e);
                }
                break;
            }
        }
    }

    private byte[] addAdtsPacket(byte[] rawAac, int rawLen) {
        int packetLen = rawLen + 7;
        byte[] packet = new byte[packetLen];

        int profile = 2; // AAC-LC
        int freqIdx = 4; // 44100Hz
        int chanCfg = 2; // Stereo

        packet[0] = (byte) 0xFF;
        packet[1] = (byte) 0xF1; // MPEG-4, layer 0, no CRC
        packet[2] = (byte) (((profile - 1) << 6) + (freqIdx << 2) + (chanCfg >> 2));
        packet[3] = (byte) (((chanCfg & 3) << 6) + (packetLen >> 11));
        packet[4] = (byte) ((packetLen & 0x7FF) >> 3);
        packet[5] = (byte) (((packetLen & 7) << 5) + 0x1F);
        packet[6] = (byte) 0xFC;

        System.arraycopy(rawAac, 0, packet, 7, rawLen);
        return packet;
    }

    private void stopScreenCapture() {
        isEncoding = false;
        isAudioEncoding = false;

        // Broadcast to update UI in MainActivity
        Intent intent = new Intent("com.example.droidlink.ACTION_STOP_UI");
        intent.setPackage(getPackageName());
        sendBroadcast(intent);

        // Stop streaming server and close client sockets -> sends EOF to receiver.py -> closes ffplay
        if (streamingServer != null) {
            streamingServer.stop();
            streamingServer = null;
        }

        if (audioStreamingServer != null) {
            audioStreamingServer.stop();
            audioStreamingServer = null;
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

        if (audioThread != null) {
            try {
                audioThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            audioThread = null;
        }

        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }

        if (audioEncoder != null) {
            try {
                audioEncoder.stop();
                audioEncoder.release();
            } catch (Exception ignored) {}
            audioEncoder = null;
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
