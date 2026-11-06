# Fast Hotspot (Android)

Share your phone's full 4G/5G speed with a laptop, tablet or another phone.

## Why the built-in hotspot is slow

Many carriers spot tethered traffic and slow it down, or cap it at a small
hotspot allowance, even though the phone itself gets full speed. They spot it
in a few ways: the separate tethering APN, packet TTL values, and the Android
tethering flag.

## How Fast Hotspot works

The approach is the same as "WiFi Hotspot" / NetShare-style apps:

1. **Wi-Fi Direct group.** The phone starts a Wi-Fi Direct group, and other
   devices see it as a normal Wi-Fi network (`DIRECT-FH-<name>`). You choose
   the password and the band (5 GHz is the default, for speed). This doesn't
   use Android's tethering at all.
2. **Built-in proxy** on port `8282`. It handles HTTP, HTTPS (CONNECT),
   SOCKS5 and SOCKS4/4a. Devices send their traffic to the proxy, and the
   **app opens the connections itself**. To the carrier it looks like normal
   phone traffic, sent over the phone's mobile-data connection.
3. **Auto-config (PAC) file** at `http://192.168.49.1:8282/proxy.pac`. Each
   device only needs this one URL.
4. **Performance:**
   - A high-performance Wi-Fi lock and a CPU wake lock stop the link from
     slowing down when the screen is off.
   - 64 KB buffers, TCP_NODELAY and large socket buffers.
   - Optionally, upstream connections can be forced onto the cellular network
     even while the phone is on Wi-Fi.
5. **Live stats:** connected devices, open connections, download/upload speed
   in Mbps, and total data. A notification shows the status and has a Stop
   button.

## Using it

1. Install the APK (see **Building**). Turn Wi-Fi **on** (you don't need to
   join a network) and turn the built-in hotspot **off**.
2. Open Fast Hotspot, set a name and password, choose 5 GHz and tap
   **Start hotspot**. Grant the Nearby devices / Location permission.
3. On each other device:
   - Join the Wi-Fi network shown in the app.
   - Set that network's proxy to **Automatic** with the PAC URL shown, or
     enter the server `192.168.49.1` and port `8282` by hand:
     - **Windows:** Settings → Network & Internet → Proxy → Use setup script
     - **macOS:** Wi-Fi → Details → Proxies → Automatic proxy configuration
     - **iPhone/iPad:** Settings → Wi-Fi → (i) → Configure Proxy → Automatic
     - **Android:** Wi-Fi → network → Edit → Advanced → Proxy → Proxy Auto-Config
4. **Share setup instructions** sends all of this as text to the other device.

Requirements: Android 10 or newer, and a phone with Wi-Fi Direct (almost all
phones have it).

## Limitations

- Only traffic that goes through the system proxy is covered. That includes
  browsers, most apps, streaming and downloads. Raw UDP (some games, some
  video-call apps) and VPN clients won't go through.
- Some older laptops' Wi-Fi cards can't see 5 GHz networks. Use 2.4 GHz or
  Auto for those.
- Check your mobile plan's terms. Some plans don't allow using phone data on
  other devices beyond the hotspot allowance.

## Building

The CI workflow `.github/workflows/hotspot-android.yml` builds the APK on
every push. Download it from the run's **Artifacts**
(`FastHotspot-apk`). The release APK is signed with the debug key, so it
installs directly. Allow "Install unknown apps" for your browser or file
manager.

To build locally (needs JDK 17 and the Android SDK):

```bash
cd hotspot-app
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

You can also open `hotspot-app/` in Android Studio and press Run.

To run the proxy engine tests (plain JVM, no SDK needed):

```bash
./proxy-test/run.sh
```

## Code layout

| File | Purpose |
| --- | --- |
| `app/src/main/java/.../proxy/ProxyServer.java` | HTTP/HTTPS/SOCKS proxy + PAC server (pure Java) |
| `app/src/main/java/.../HotspotService.java` | Foreground service: Wi-Fi Direct group, cellular binding, locks, stats |
| `app/src/main/java/.../ui/MainActivity.java` | Settings, permissions, live status and setup instructions |
| `proxy-test/` | Desktop tests for the proxy engine |
