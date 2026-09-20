package com.example.droidlink.service;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class StreamingServer {

    private static final String TAG = "DroidLinkServer";
    private static final int PORT = 8080;

    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean isRunning = false;
    private final List<OutputStream> clients = new CopyOnWriteArrayList<>();

    public void start() {
        if (isRunning) return;
        isRunning = true;

        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.d(TAG, "Local Streaming Server started on port " + PORT + " (IP: " + getLocalIpAddress() + ")");

                while (isRunning) {
                    Socket clientSocket = serverSocket.accept();
                    Log.d(TAG, "Client connected: " + clientSocket.getRemoteSocketAddress());

                    OutputStream clientOut = clientSocket.getOutputStream();
                    clients.add(clientOut);
                }
            } catch (IOException e) {
                if (isRunning) {
                    Log.e(TAG, "Streaming server error", e);
                }
            }
        }, "StreamingServerThread");
        serverThread.start();
    }

    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing server socket", e);
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

        Log.d(TAG, "Local Streaming Server stopped");
    }

    public void broadcastData(byte[] data) {
        if (!isRunning || data == null || data.length == 0) return;

        // Prepend 4-byte big-endian length header for H.264 NAL packet framing
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
                Log.d(TAG, "Client disconnected during broadcast");
            }
        }
    }

    public static String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "Failed to get local IP", ex);
        }
        return "127.0.0.1";
    }
}
