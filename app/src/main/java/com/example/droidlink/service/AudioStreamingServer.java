package com.example.droidlink.service;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class AudioStreamingServer {

    private static final String TAG = "AudioStreamingServer";
    private static final int PORT = 8081;

    private static AudioStreamingServer instance;

    public static synchronized AudioStreamingServer getInstance() {
        if (instance == null) {
            instance = new AudioStreamingServer();
        }
        return instance;
    }

    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean isRunning = false;

    private final List<OutputStream> clients = new CopyOnWriteArrayList<>();

    public synchronized void start() {
        if (isRunning && serverSocket != null && !serverSocket.isClosed()) {
            return;
        }

        stop();
        isRunning = true;

        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                serverSocket.setReuseAddress(true);
                Log.d(TAG, "Audio Streaming Server started on port " + PORT);

                while (isRunning) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        clientSocket.setTcpNoDelay(true);
                        clientSocket.setSendBufferSize(32768);

                        Log.d(TAG, "Audio client connected: " + clientSocket.getRemoteSocketAddress());
                        OutputStream clientOut = clientSocket.getOutputStream();
                        clients.add(clientOut);
                    } catch (IOException e) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting audio client", e);
                        }
                    }
                }
            } catch (IOException e) {
                if (isRunning) {
                    Log.e(TAG, "Audio streaming server socket error", e);
                }
            }
        }, "AudioServerThread");

        serverThread.start();
    }

    public synchronized void stop() {
        isRunning = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing audio server socket", e);
        }

        for (OutputStream out : clients) {
            try {
                out.close();
            } catch (IOException ignored) {}
        }
        clients.clear();

        if (serverThread != null) {
            serverThread.interrupt();
            serverThread = null;
        }

        Log.d(TAG, "Audio Streaming Server stopped");
    }

    public void broadcastData(byte[] data) {
        if (!isRunning || data == null || data.length == 0) return;

        byte[] packet = new byte[4 + data.length];
        packet[0] = (byte) (data.length >> 24);
        packet[1] = (byte) (data.length >> 16);
        packet[2] = (byte) (data.length >> 8);
        packet[3] = (byte) data.length;
        System.arraycopy(data, 0, packet, 4, data.length);

        for (OutputStream out : clients) {
            try {
                out.write(packet);
                out.flush();
            } catch (IOException e) {
                clients.remove(out);
                try {
                    out.close();
                } catch (IOException ignored) {}
                Log.d(TAG, "Audio client disconnected");
            }
        }
    }
}
