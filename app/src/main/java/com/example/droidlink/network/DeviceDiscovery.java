package com.example.droidlink.network;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

public class DeviceDiscovery {

    private static final String TAG = "DeviceDiscovery";
    private static final int DISCOVERY_PORT = 37020;
    private static final String REQUEST_MESSAGE = "DROIDLINK_DISCOVERY_REQUEST";

    private Thread listenerThread;
    private volatile boolean isRunning = false;

    public void start() {
        if (isRunning) return;
        isRunning = true;

        listenerThread = new Thread(() -> {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket(DISCOVERY_PORT);
                socket.setBroadcast(true);
                Log.d(TAG, "DeviceDiscovery UDP listener started on port " + DISCOVERY_PORT);

                byte[] receiveBuffer = new byte[1024];

                while (isRunning) {
                    DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    socket.receive(receivePacket);

                    String receivedMessage = new String(
                            receivePacket.getData(),
                            receivePacket.getOffset(),
                            receivePacket.getLength(),
                            StandardCharsets.UTF_8
                    ).trim();

                    Log.d(TAG, "Received UDP packet from " + receivePacket.getAddress() + ":" + receivePacket.getPort() + " -> " + receivedMessage);

                    if (receivedMessage.contains(REQUEST_MESSAGE)) {
                        InetAddress clientAddress = receivePacket.getAddress();
                        int clientPort = receivePacket.getPort();

                        String responseMessage = DiscoveryMessage.toJsonString();
                        byte[] sendData = responseMessage.getBytes(StandardCharsets.UTF_8);

                        DatagramPacket sendPacket = new DatagramPacket(
                                sendData,
                                sendData.length,
                                clientAddress,
                                clientPort
                        );

                        socket.send(sendPacket);
                        Log.d(TAG, "Sent DROIDLINK_DISCOVERY response to " + clientAddress.getHostAddress() + ":" + clientPort);
                    }
                }
            } catch (Exception e) {
                if (isRunning) {
                    Log.e(TAG, "UDP listener error", e);
                }
            } finally {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            }
        }, "DeviceDiscoveryListenerThread");

        listenerThread.start();
    }

    public void stop() {
        isRunning = false;
        if (listenerThread != null) {
            listenerThread.interrupt();
            listenerThread = null;
        }
        Log.d(TAG, "DeviceDiscovery stopped");
    }
}
