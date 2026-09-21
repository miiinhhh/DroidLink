package com.example.droidlink.network;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class DeviceDiscovery {

    private static final String TAG = "DeviceDiscovery";
    private static final int DISCOVERY_PORT = 37020;
    private static final int BROADCAST_INTERVAL_MS = 2000;

    private Thread discoveryThread;
    private volatile boolean isRunning = false;

    public void start() {
        if (isRunning) return;
        isRunning = true;

        discoveryThread = new Thread(() -> {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                socket.setBroadcast(true);

                String message = DiscoveryMessage.toJsonString();
                byte[] buffer = message.getBytes("UTF-8");

                InetAddress broadcastAddress =
                        InetAddress.getByName("192.168.1.4");

                while (isRunning) {
                    DatagramPacket packet = new DatagramPacket(
                            buffer,
                            buffer.length,
                            broadcastAddress,
                            DISCOVERY_PORT
                    );
                    socket.send(packet);
                    Log.d(TAG, "Sent UDP discovery broadcast: " + message);

                    Thread.sleep(BROADCAST_INTERVAL_MS);
                }
            } catch (Exception e) {
                if (isRunning) {
                    Log.e(TAG, "UDP discovery error", e);
                }
            } finally {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            }
        }, "DeviceDiscoveryThread");

        discoveryThread.start();
        Log.d(TAG, "DeviceDiscovery started");
    }

    public void stop() {
        isRunning = false;
        if (discoveryThread != null) {
            discoveryThread.interrupt();
            discoveryThread = null;
        }
        Log.d(TAG, "DeviceDiscovery stopped");
    }
}
