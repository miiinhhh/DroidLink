package com.example.droidlink.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.media.projection.MediaProjectionManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.droidlink.R;
import com.example.droidlink.network.DeviceDiscovery;
import com.example.droidlink.service.ScreenCaptureService;
import com.example.droidlink.service.StreamingServer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "DroidLink";

    private enum StatusType {
        WAITING,
        CONNECTING,
        MIRRORING,
        PERMISSION_DENIED
    }

    private Button btnStartMirroring;
    private Button btnStopMirroring;
    private TextView tvStatus;
    private ImageView viewStatusDot;

    private TextView tvWifiName;
    private TextView tvIpAddress;
    private Button btnCopyIp;
    private View layoutLiveStats;

    private MediaProjectionManager mediaProjectionManager;
    private DeviceDiscovery deviceDiscovery;

    private final ActivityResultLauncher<Intent> screenCaptureLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK
                                && result.getData() != null) {

                            updateStatus(StatusType.MIRRORING);

                            startScreenCaptureService(
                                    result.getData()
                            );

                            btnStartMirroring.setEnabled(false);
                            btnStopMirroring.setEnabled(true);

                        } else {
                            updateStatus(StatusType.PERMISSION_DENIED);

                            btnStartMirroring.setEnabled(true);
                            btnStopMirroring.setEnabled(false);
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Edge-to-Edge window insets padding
        View rootLayout = findViewById(R.id.rootLayout);
        if (rootLayout != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(
                        v.getPaddingLeft(),
                        insets.top,
                        v.getPaddingRight(),
                        insets.bottom
                );
                return windowInsets;
            });
        }

        // Initialize UI components
        btnStartMirroring = findViewById(R.id.btnStartMirroring);
        btnStopMirroring = findViewById(R.id.btnStopMirroring);
        tvStatus = findViewById(R.id.tvStatus);
        viewStatusDot = findViewById(R.id.viewStatusDot);

        tvWifiName = findViewById(R.id.tvWifiName);
        tvIpAddress = findViewById(R.id.tvIpAddress);
        btnCopyIp = findViewById(R.id.btnCopyIp);
        layoutLiveStats = findViewById(R.id.layoutLiveStats);

        mediaProjectionManager =
                (MediaProjectionManager)
                        getSystemService(MEDIA_PROJECTION_SERVICE);

        btnStartMirroring.setOnClickListener(v ->
                checkAudioPermissionAndRequest()
        );

        btnStopMirroring.setOnClickListener(v ->
                stopScreenCaptureService()
        );

        if (btnCopyIp != null) {
            btnCopyIp.setOnClickListener(v -> copyIpToClipboard());
        }

        // Display current IP & Wi-Fi network
        updateNetworkInfo();

        // Initial status: Waiting for client connection
        updateStatus(StatusType.WAITING);

        // Start discovery and streaming server on app launch (waiting for client connection)
        deviceDiscovery = new DeviceDiscovery();
        deviceDiscovery.start();

        StreamingServer streamingServer = StreamingServer.getInstance();
        streamingServer.start();
        streamingServer.setOnClientConnectedListener(() ->
                runOnUiThread(() -> {
                    updateStatus(StatusType.CONNECTING);
                    checkAudioPermissionAndRequest();
                })
        );
    }

    private void updateStatus(StatusType status) {
        if (tvStatus == null || viewStatusDot == null) return;

        switch (status) {
            case WAITING:
                tvStatus.setText(R.string.status_waiting);
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_text));
                viewStatusDot.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_dot)));
                if (layoutLiveStats != null) {
                    layoutLiveStats.setVisibility(View.GONE);
                }
                break;
            case CONNECTING:
                tvStatus.setText(R.string.status_connecting);
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_text));
                viewStatusDot.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_dot)));
                break;
            case MIRRORING:
                tvStatus.setText(R.string.status_mirroring);
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_active_text));
                viewStatusDot.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_active_dot)));
                if (layoutLiveStats != null) {
                    layoutLiveStats.setVisibility(View.VISIBLE);
                }
                break;
            case PERMISSION_DENIED:
                tvStatus.setText(R.string.status_permission_denied);
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.error));
                viewStatusDot.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.error)));
                if (layoutLiveStats != null) {
                    layoutLiveStats.setVisibility(View.GONE);
                }
                break;
        }
    }

    private void updateNetworkInfo() {
        String ipAddress = getLocalIpAddress();
        if (tvIpAddress != null) {
            tvIpAddress.setText(ipAddress != null ? ipAddress : "127.0.0.1");
        }

        if (tvWifiName != null) {
            tvWifiName.setText(getWifiSsid());
        }
    }

    private void copyIpToClipboard() {
        if (tvIpAddress == null) return;
        String ip = tvIpAddress.getText().toString();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("DroidLink IP", ip);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, R.string.toast_ip_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                if (intf.isLoopback() || !intf.isUp()) continue;
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting IP address", e);
        }
        return null;
    }

    private String getWifiSsid() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                        if (wifiManager != null) {
                            WifiInfo info = wifiManager.getConnectionInfo();
                            if (info != null && info.getSSID() != null) {
                                String ssid = info.getSSID();
                                if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() > 2) {
                                    ssid = ssid.substring(1, ssid.length() - 1);
                                }
                                if (!"<unknown ssid>".equalsIgnoreCase(ssid)) {
                                    return ssid;
                                }
                            }
                        }
                        return "Wi-Fi (Đã kết nối)";
                    } else if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        return "Mạng di động (4G/5G)";
                    }
                }
            }
        } catch (Exception ignored) {}
        return getString(R.string.wifi_not_connected);
    }

    private final ActivityResultLauncher<String> audioPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    isGranted -> {
                        requestScreenCapturePermission();
                    }
            );

    private void checkAudioPermissionAndRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO);
                return;
            }
        }
        requestScreenCapturePermission();
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

    private void stopScreenCaptureService() {
        Intent serviceIntent =
                new Intent(
                        this,
                        ScreenCaptureService.class
                );

        stopService(serviceIntent);

        // Restart streaming server to listen for new client connections
        StreamingServer streamingServer = StreamingServer.getInstance();
        streamingServer.start();
        streamingServer.setOnClientConnectedListener(() ->
                runOnUiThread(() -> {
                    updateStatus(StatusType.CONNECTING);
                    requestScreenCapturePermission();
                })
        );

        updateStatus(StatusType.WAITING);

        btnStartMirroring.setEnabled(true);
        btnStopMirroring.setEnabled(false);
    }

    private final android.content.BroadcastReceiver stopUiReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            updateStatus(StatusType.WAITING);
            btnStartMirroring.setEnabled(true);
            btnStopMirroring.setEnabled(false);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        updateNetworkInfo();
        android.content.IntentFilter filter = new android.content.IntentFilter("com.example.droidlink.ACTION_STOP_UI");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopUiReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stopUiReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(stopUiReceiver);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        if (deviceDiscovery != null) {
            deviceDiscovery.stop();
        }
        StreamingServer.getInstance().stop();
        super.onDestroy();
    }
}
