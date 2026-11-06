package com.royleib.fasthotspot;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** Process-wide hotspot status shared between the service and the UI. */
public final class HotspotState {

    public enum Status { STOPPED, STARTING, RUNNING, ERROR }

    public interface Listener {
        void onStateChanged(HotspotState state);
    }

    private static final HotspotState INSTANCE = new HotspotState();

    public static HotspotState get() { return INSTANCE; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public volatile Status status = Status.STOPPED;
    public volatile String message = "";
    public volatile String networkName = "";
    public volatile String passphrase = "";
    public volatile String proxyHost = "192.168.49.1";
    public volatile int proxyPort = HotspotService.PROXY_PORT;
    public volatile String band = "";
    public volatile String upstream = "";
    public volatile int clients;
    public volatile int connections;
    public volatile long bytesUp;
    public volatile long bytesDown;
    public volatile long downRate; // bytes/second
    public volatile long upRate;

    private HotspotState() { }

    public void addListener(Listener l) {
        listeners.add(l);
        l.onStateChanged(this);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public void notifyChanged() {
        main.post(() -> {
            for (Listener l : listeners) l.onStateChanged(this);
        });
    }

    public void reset() {
        status = Status.STOPPED;
        message = "";
        networkName = "";
        passphrase = "";
        band = "";
        upstream = "";
        clients = 0;
        connections = 0;
        bytesUp = 0;
        bytesDown = 0;
        downRate = 0;
        upRate = 0;
    }
}
