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

    private static StreamingServer instance;

    public static synchronized StreamingServer getInstance() {
        if (instance == null) {
            instance = new StreamingServer();
        }
        return instance;
    }

    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean isRunning = false;

    private final List<OutputStream> clients =
            new CopyOnWriteArrayList<>();

    private volatile byte[] sps;
    private volatile byte[] pps;
    private volatile byte[] latestIdrFrame;

    public interface OnClientConnectedListener {
        void onClientConnected();
    }

    private OnClientConnectedListener clientConnectedListener;

    public void setOnClientConnectedListener(OnClientConnectedListener listener) {
        this.clientConnectedListener = listener;
    }

    public synchronized void start() {
        if (isRunning && serverSocket != null && !serverSocket.isClosed()) {
            return;
        }

        stop(); // Ensure clean state

        isRunning = true;

        serverThread = new Thread(() -> {

            try {
                serverSocket = new ServerSocket(PORT);
                serverSocket.setReuseAddress(true);

                Log.d(
                        TAG,
                        "Local Streaming Server started on port "
                                + PORT
                                + " (IP: "
                                + getLocalIpAddress()
                                + ")"
                );

                while (isRunning) {
                    try {
                        Socket clientSocket = serverSocket.accept();

                        clientSocket.setTcpNoDelay(true);
                        clientSocket.setSendBufferSize(65536);

                        Log.d(
                                TAG,
                                "Client connected: "
                                        + clientSocket.getRemoteSocketAddress()
                        );

                        OutputStream clientOut =
                                clientSocket.getOutputStream();

                        clients.add(clientOut);

                        // Send SPS + PPS + latest IDR to newly connected client
                        sendInitializationFrames(clientOut);

                        // Notify listener (MainActivity) that a client has connected
                        if (clientConnectedListener != null) {
                            clientConnectedListener.onClientConnected();
                        }
                    } catch (IOException e) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting client connection", e);
                        }
                    }
                }

            } catch (IOException e) {

                if (isRunning) {
                    Log.e(
                            TAG,
                            "Streaming server socket error",
                            e
                    );
                }
            }

        }, "StreamingServerThread");

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

    public void setCodecConfig(byte[] sps, byte[] pps) {
        this.sps = copyBytes(sps);
        this.pps = copyBytes(pps);
        Log.d(TAG, "Codec config updated: SPS=" + (this.sps != null ? this.sps.length : 0) + ", PPS=" + (this.pps != null ? this.pps.length : 0));
    }

    public void broadcastData(byte[] data, boolean isIdr) {
        if (!isRunning || data == null || data.length == 0) return;

        if (isIdr) {
            this.latestIdrFrame = copyBytes(data);
            Log.d(TAG, "Latest IDR frame updated, size=" + data.length);
        }

        for (OutputStream out : clients) {
            try {
                if (isIdr) {
                    if (sps != null) {
                        sendPacket(out, sps);
                    }
                    if (pps != null) {
                        sendPacket(out, pps);
                    }
                }
                sendPacket(out, data);
            } catch (IOException e) {
                clients.remove(out);
                try {
                    out.close();
                } catch (IOException ignored) {}
                Log.d(TAG, "Client disconnected during broadcast");
            }
        }
    }

    private void sendInitializationFrames(OutputStream out) {
        try {
            if (sps != null) {
                sendPacket(out, sps);
            }
            if (pps != null) {
                sendPacket(out, pps);
            }
            if (latestIdrFrame != null) {
                sendPacket(out, latestIdrFrame);
                Log.d(TAG, "Sent SPS + PPS + latest IDR to new client");
            } else {
                Log.d(TAG, "Sent SPS + PPS to new client (IDR not available yet)");
            }
        } catch (IOException e) {
            clients.remove(out);
            try {
                out.close();
            } catch (IOException ignored) {}
            Log.d(TAG, "Client disconnected while sending initialization frames");
        }
    }

    private void sendPacket(OutputStream out, byte[] data) throws IOException {
        byte[] packet = buildPacket(data);
        out.write(packet);
        out.flush();
    }

    private byte[] buildPacket(byte[] data) {
        byte[] packet = new byte[4 + data.length];
        packet[0] = (byte) (data.length >> 24);
        packet[1] = (byte) (data.length >> 16);
        packet[2] = (byte) (data.length >> 8);
        packet[3] = (byte) data.length;
        System.arraycopy(data, 0, packet, 4, data.length);
        return packet;
    }

    private byte[] copyBytes(byte[] src) {
        if (src == null) return null;
        byte[] dest = new byte[src.length];
        System.arraycopy(src, 0, dest, 0, src.length);
        return dest;
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
