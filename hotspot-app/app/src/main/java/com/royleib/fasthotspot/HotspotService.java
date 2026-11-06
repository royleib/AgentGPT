package com.royleib.fasthotspot;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.royleib.fasthotspot.proxy.ProxyServer;
import com.royleib.fasthotspot.ui.MainActivity;

import java.io.IOException;

/**
 * Runs the Wi-Fi Direct hotspot and the proxy server in the foreground.
 *
 * How it works: the phone creates a Wi-Fi Direct group (which laptops, tablets
 * and other phones see as a normal Wi-Fi network) and runs a proxy server on it.
 * Devices that use the proxy have their traffic opened by this app on the phone,
 * so it leaves through the phone's own mobile-data connection at full speed
 * instead of the carrier's tethering lane.
 */
public class HotspotService extends Service {

    public static final String ACTION_START = "com.royleib.fasthotspot.START";
    public static final String ACTION_STOP = "com.royleib.fasthotspot.STOP";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_BAND = "band"; // "5", "2", "auto"
    public static final String EXTRA_FORCE_CELLULAR = "force_cellular";

    public static final int PROXY_PORT = 8282;

    private static final String CHANNEL_ID = "hotspot";
    private static final int NOTIFICATION_ID = 1;
    private static final long POLL_MS = 2000;

    private final HotspotState state = HotspotState.get();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    private ProxyServer proxy;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager.NetworkCallback cellularCallback;

    private String requestedName;
    private String requestedPassword;
    private String requestedBand;
    private int createAttempts;
    private long lastUp, lastDown, lastSample;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            shutdown(null);
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && state.status != HotspotState.Status.RUNNING
                && state.status != HotspotState.Status.STARTING) {
            requestedName = intent.getStringExtra(EXTRA_NAME);
            requestedPassword = intent.getStringExtra(EXTRA_PASSWORD);
            requestedBand = intent.getStringExtra(EXTRA_BAND);
            boolean forceCellular = intent.getBooleanExtra(EXTRA_FORCE_CELLULAR, true);
            begin(forceCellular);
        }
        return START_NOT_STICKY;
    }

    private void begin(boolean forceCellular) {
        state.reset();
        state.status = HotspotState.Status.STARTING;
        state.message = getString(R.string.status_starting);
        state.notifyChanged();

        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.status_starting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);

        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null || !wifi.isWifiEnabled()) {
            shutdown(getString(R.string.error_wifi_off));
            return;
        }

        acquireLocks(wifi);
        if (forceCellular) requestCellular();
        else state.upstream = getString(R.string.upstream_default);

        p2p = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (p2p == null) {
            shutdown(getString(R.string.error_no_p2p));
            return;
        }
        channel = p2p.initialize(this, getMainLooper(), () -> shutdown(getString(R.string.error_p2p_lost)));
        createAttempts = 0;
        // Remove any stale group first, then create ours.
        p2p.removeGroup(channel, new SimpleListener(this::createGroup, this::createGroup));
    }

    @SuppressLint("MissingPermission")
    private void createGroup() {
        if (state.status != HotspotState.Status.STARTING) return;
        createAttempts++;
        WifiP2pConfig config = new WifiP2pConfig.Builder()
                .setNetworkName(requestedName)
                .setPassphrase(requestedPassword)
                .setGroupOperatingBand(bandConstant(requestedBand))
                .enablePersistentMode(false)
                .build();
        p2p.createGroup(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                handler.postDelayed(HotspotService.this::onGroupCreated, 800);
            }

            @Override
            public void onFailure(int reason) {
                if (reason == WifiP2pManager.BUSY && createAttempts < 4) {
                    handler.postDelayed(HotspotService.this::createGroup, 1500);
                } else if (!"auto".equals(requestedBand) && createAttempts < 5) {
                    // Some phones refuse a fixed band (e.g. 5 GHz not allowed here); fall back.
                    requestedBand = "auto";
                    handler.postDelayed(HotspotService.this::createGroup, 1000);
                } else {
                    shutdown(getString(R.string.error_create_group, reasonText(reason)));
                }
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void onGroupCreated() {
        p2p.requestConnectionInfo(channel, info -> {
            if (info != null && info.groupOwnerAddress != null) {
                state.proxyHost = info.groupOwnerAddress.getHostAddress();
            }
            p2p.requestGroupInfo(channel, group -> {
                if (group == null) {
                    // Group not reported yet; ask again shortly.
                    handler.postDelayed(this::onGroupCreated, 700);
                    return;
                }
                applyGroup(group);
                startProxy();
            });
        });
    }

    private void applyGroup(WifiP2pGroup group) {
        state.networkName = group.getNetworkName();
        state.passphrase = group.getPassphrase();
        state.clients = group.getClientList().size();
        int freq = group.getFrequency();
        if (freq >= 4900) state.band = "5 GHz (" + freq + " MHz)";
        else if (freq > 0) state.band = "2.4 GHz (" + freq + " MHz)";
        else state.band = "";
    }

    private void startProxy() {
        if (state.status != HotspotState.Status.STARTING) return;
        proxy = new ProxyServer(PROXY_PORT, state.proxyHost);
        Network cell = cellularNetwork;
        if (cell != null) proxy.setUpstream(cell.getSocketFactory(), cell::getAllByName);
        try {
            proxy.start();
        } catch (IOException e) {
            shutdown(getString(R.string.error_proxy, e.getMessage()));
            return;
        }
        state.proxyPort = proxy.getPort();
        state.status = HotspotState.Status.RUNNING;
        state.message = getString(R.string.status_running);
        lastSample = System.currentTimeMillis();
        state.notifyChanged();
        updateNotification();
        handler.postDelayed(poll, POLL_MS);
    }

    private final Runnable poll = new Runnable() {
        @SuppressLint("MissingPermission")
        @Override
        public void run() {
            if (state.status != HotspotState.Status.RUNNING || proxy == null) return;
            long now = System.currentTimeMillis();
            long up = proxy.getBytesUp();
            long down = proxy.getBytesDown();
            long dt = Math.max(1, now - lastSample);
            state.upRate = (up - lastUp) * 1000 / dt;
            state.downRate = (down - lastDown) * 1000 / dt;
            lastUp = up;
            lastDown = down;
            lastSample = now;
            state.bytesUp = up;
            state.bytesDown = down;
            state.connections = proxy.getActiveConnections();
            p2p.requestGroupInfo(channel, group -> {
                if (group != null) applyGroup(group);
                state.notifyChanged();
                updateNotification();
            });
            handler.postDelayed(this, POLL_MS);
        }
    };

    // ------------------------------------------------------------ cellular

    private volatile Network cellularNetwork;

    private void requestCellular() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        state.upstream = getString(R.string.upstream_waiting);
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        cellularCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                cellularNetwork = network;
                ProxyServer p = proxy;
                if (p != null) p.setUpstream(network.getSocketFactory(), network::getAllByName);
                state.upstream = getString(R.string.upstream_cellular);
                state.notifyChanged();
            }

            @Override
            public void onLost(Network network) {
                if (network.equals(cellularNetwork)) {
                    cellularNetwork = null;
                    ProxyServer p = proxy;
                    if (p != null) p.setUpstream(null, null);
                    state.upstream = getString(R.string.upstream_default);
                    state.notifyChanged();
                }
            }
        };
        try {
            cm.requestNetwork(req, cellularCallback);
        } catch (SecurityException | IllegalArgumentException e) {
            cellularCallback = null;
            state.upstream = getString(R.string.upstream_default);
        }
    }

    // ------------------------------------------------------------- locks

    private void acquireLocks(WifiManager wifi) {
        // Keep Wi-Fi in its highest-performance mode and the CPU awake so the
        // hotspot doesn't slow down or drop when the screen turns off.
        @SuppressWarnings("deprecation")
        int mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        wifiLock = wifi.createWifiLock(mode, "FastHotspot:wifi");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FastHotspot:cpu");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wifiLock = null;
        wakeLock = null;
    }

    // ---------------------------------------------------------- shutdown

    private void shutdown(String error) {
        handler.removeCallbacksAndMessages(null);
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        if (p2p != null && channel != null) {
            p2p.removeGroup(channel, null);
            channel.close();
        }
        channel = null;
        if (cellularCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            try {
                if (cm != null) cm.unregisterNetworkCallback(cellularCallback);
            } catch (IllegalArgumentException ignored) { }
            cellularCallback = null;
            cellularNetwork = null;
        }
        releaseLocks();
        state.reset();
        if (error != null) {
            state.status = HotspotState.Status.ERROR;
            state.message = error;
        }
        state.notifyChanged();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (state.status == HotspotState.Status.RUNNING || state.status == HotspotState.Status.STARTING) {
            shutdown(null);
        }
        super.onDestroy();
    }

    // ------------------------------------------------------ notification

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, HotspotService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_hotspot)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build())
                .build();
    }

    private void updateNotification() {
        String text = getString(R.string.notification_text, state.networkName, state.clients,
                Format.rate(state.downRate));
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text));
    }

    // ------------------------------------------------------------ helpers

    private static int bandConstant(String band) {
        if ("5".equals(band)) return WifiP2pConfig.GROUP_OWNER_BAND_5GHZ;
        if ("2".equals(band)) return WifiP2pConfig.GROUP_OWNER_BAND_2GHZ;
        return WifiP2pConfig.GROUP_OWNER_BAND_AUTO;
    }

    private String reasonText(int reason) {
        switch (reason) {
            case WifiP2pManager.P2P_UNSUPPORTED: return getString(R.string.reason_unsupported);
            case WifiP2pManager.BUSY: return getString(R.string.reason_busy);
            default: return getString(R.string.reason_error, reason);
        }
    }

    /** ActionListener that runs the same/different callback on success or failure. */
    private static final class SimpleListener implements WifiP2pManager.ActionListener {
        private final Runnable success;
        private final Runnable failure;

        SimpleListener(Runnable success, Runnable failure) {
            this.success = success;
            this.failure = failure;
        }

        @Override public void onSuccess() { success.run(); }
        @Override public void onFailure(int reason) { failure.run(); }
    }
}
