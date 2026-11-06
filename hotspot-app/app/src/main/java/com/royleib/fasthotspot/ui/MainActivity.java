package com.royleib.fasthotspot.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.royleib.fasthotspot.Format;
import com.royleib.fasthotspot.HotspotService;
import com.royleib.fasthotspot.HotspotState;
import com.royleib.fasthotspot.R;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements HotspotState.Listener {

    private static final int REQ_PERMS = 1;
    /** Wi-Fi Direct network names must start with "DIRECT-" plus two characters. */
    private static final String NAME_PREFIX = "DIRECT-FH-";
    private static final int MAX_SSID_BYTES = 32;

    private EditText nameInput;
    private EditText passwordInput;
    private RadioGroup bandGroup;
    private CheckBox forceCellular;
    private Button toggle;
    private Button share;
    private TextView statusText;
    private TextView details;
    private TextView stats;
    private View settingsPanel;
    private View runningPanel;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        nameInput = findViewById(R.id.name);
        passwordInput = findViewById(R.id.password);
        bandGroup = findViewById(R.id.band);
        forceCellular = findViewById(R.id.force_cellular);
        toggle = findViewById(R.id.toggle);
        share = findViewById(R.id.share);
        statusText = findViewById(R.id.status);
        details = findViewById(R.id.details);
        stats = findViewById(R.id.stats);
        settingsPanel = findViewById(R.id.settings_panel);
        runningPanel = findViewById(R.id.running_panel);

        nameInput.setText(prefs.getString("name", "FastNet"));
        String pw = prefs.getString("password", null);
        if (pw == null) {
            pw = randomPassword();
            prefs.edit().putString("password", pw).apply();
        }
        passwordInput.setText(pw);
        String band = prefs.getString("band", "5");
        bandGroup.check("2".equals(band) ? R.id.band_2 : "auto".equals(band) ? R.id.band_auto : R.id.band_5);
        forceCellular.setChecked(prefs.getBoolean("force_cellular", true));

        toggle.setOnClickListener(v -> onToggle());
        share.setOnClickListener(v -> shareSetup());
        details.setOnLongClickListener(v -> {
            copy(details.getText().toString());
            return true;
        });
        findViewById(R.id.help).setOnClickListener(v -> showHelp());
    }

    @Override
    protected void onStart() {
        super.onStart();
        HotspotState.get().addListener(this);
    }

    @Override
    protected void onStop() {
        HotspotState.get().removeListener(this);
        super.onStop();
    }

    // ------------------------------------------------------------- start/stop

    private void onToggle() {
        HotspotState.Status s = HotspotState.get().status;
        if (s == HotspotState.Status.RUNNING || s == HotspotState.Status.STARTING) {
            startService(new Intent(this, HotspotService.class).setAction(HotspotService.ACTION_STOP));
            return;
        }
        if (!validateInputs()) return;
        if (!ensurePermissions()) return;
        if (!ensureRadios()) return;
        launch();
    }

    private boolean validateInputs() {
        String name = nameInput.getText().toString().trim();
        String pw = passwordInput.getText().toString();
        if (name.isEmpty()) {
            nameInput.setError(getString(R.string.error_name_empty));
            return false;
        }
        int maxName = MAX_SSID_BYTES - NAME_PREFIX.length();
        if (name.getBytes(StandardCharsets.UTF_8).length > maxName) {
            nameInput.setError(getString(R.string.error_name_long, maxName));
            return false;
        }
        if (pw.length() < 8 || pw.length() > 63) {
            passwordInput.setError(getString(R.string.error_password));
            return false;
        }
        return true;
    }

    private boolean ensurePermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        List<String> missing = new ArrayList<>();
        for (String p : needed) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) return true;
        requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_PERMS) return;
        for (int i = 0; i < permissions.length; i++) {
            boolean optional = Manifest.permission.POST_NOTIFICATIONS.equals(permissions[i]);
            if (results[i] != PackageManager.PERMISSION_GRANTED && !optional) {
                Toast.makeText(this, R.string.error_permission, Toast.LENGTH_LONG).show();
                return;
            }
        }
        if (ensureRadios()) launch();
    }

    private boolean ensureRadios() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null && !wifi.isWifiEnabled()) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.error_wifi_off)
                    .setPositiveButton(R.string.open_settings,
                            (d, w) -> startActivity(new Intent(Settings.Panel.ACTION_WIFI)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return false;
        }
        if (Build.VERSION.SDK_INT < 33) {
            LocationManager lm = getSystemService(LocationManager.class);
            if (lm != null && !lm.isLocationEnabled()) {
                new AlertDialog.Builder(this)
                        .setMessage(R.string.error_location_off)
                        .setPositiveButton(R.string.open_settings,
                                (d, w) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return false;
            }
        }
        return true;
    }

    private void launch() {
        String name = nameInput.getText().toString().trim();
        String pw = passwordInput.getText().toString();
        int checked = bandGroup.getCheckedRadioButtonId();
        String band = checked == R.id.band_2 ? "2" : checked == R.id.band_auto ? "auto" : "5";
        prefs.edit()
                .putString("name", name)
                .putString("password", pw)
                .putString("band", band)
                .putBoolean("force_cellular", forceCellular.isChecked())
                .apply();

        Intent i = new Intent(this, HotspotService.class)
                .setAction(HotspotService.ACTION_START)
                .putExtra(HotspotService.EXTRA_NAME, NAME_PREFIX + name)
                .putExtra(HotspotService.EXTRA_PASSWORD, pw)
                .putExtra(HotspotService.EXTRA_BAND, band)
                .putExtra(HotspotService.EXTRA_FORCE_CELLULAR, forceCellular.isChecked());
        startForegroundService(i);
    }

    // -------------------------------------------------------------------- UI

    @Override
    public void onStateChanged(HotspotState s) {
        boolean active = s.status == HotspotState.Status.RUNNING || s.status == HotspotState.Status.STARTING;
        toggle.setText(active ? R.string.stop : R.string.start);
        settingsPanel.setVisibility(active ? View.GONE : View.VISIBLE);
        runningPanel.setVisibility(s.status == HotspotState.Status.RUNNING ? View.VISIBLE : View.GONE);

        switch (s.status) {
            case RUNNING:
                statusText.setText(R.string.status_running);
                statusText.setBackgroundResource(R.drawable.status_on);
                break;
            case STARTING:
                statusText.setText(R.string.status_starting);
                statusText.setBackgroundResource(R.drawable.status_wait);
                break;
            case ERROR:
                statusText.setText(s.message);
                statusText.setBackgroundResource(R.drawable.status_error);
                break;
            default:
                statusText.setText(R.string.status_stopped);
                statusText.setBackgroundResource(R.drawable.status_off);
        }

        if (s.status == HotspotState.Status.RUNNING) {
            details.setText(getString(R.string.details_template,
                    s.networkName, s.passphrase,
                    s.proxyHost, s.proxyPort,
                    pacUrl(s),
                    s.band.isEmpty() ? "-" : s.band,
                    s.upstream));
            stats.setText(getString(R.string.stats_template,
                    s.clients, s.connections,
                    Format.rate(s.downRate), Format.rate(s.upRate),
                    Format.bytes(s.bytesDown), Format.bytes(s.bytesUp)));
        }
    }

    private static String pacUrl(HotspotState s) {
        return "http://" + s.proxyHost + ":" + s.proxyPort + "/proxy.pac";
    }

    private void shareSetup() {
        HotspotState s = HotspotState.get();
        String text = getString(R.string.share_template, s.networkName, s.passphrase,
                s.proxyHost, s.proxyPort, pacUrl(s));
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text), getString(R.string.share)));
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.help_title)
                .setMessage(R.string.help_text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void copy(String text) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private static String randomPassword() {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }
}
