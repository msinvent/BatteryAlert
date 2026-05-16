package com.batteryalert.app;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Random;

public class MainActivity extends AppCompatActivity {

    // ─── Puzzle views ─────────────────────────────────────────────────────────
    private TextView statusText;
    private TextView puzzleEquationText;
    private EditText puzzleAnswerInput;
    private TextView puzzleErrorText;
    private Button disableAlertsBtn;
    private LinearLayout puzzleSection;
    private LinearLayout reenableSection;
    private Button reenableAlertsBtn;

    // ─── Puzzle values ────────────────────────────────────────────────────────
    private int puzzleX;
    private int puzzleY;
    private int puzzleZ;
    private double puzzleAnswer; // sqrt(x*y + z)

    // ─── Prefs ────────────────────────────────────────────────────────────────
    private SharedPreferences prefs;
    public static final String PREFS_NAME = "BatteryAlertPrefs";
    public static final String KEY_ENABLED = "alerts_enabled";

    // ─── Other views ─────────────────────────────────────────────────────────
    private TextView batteryLevelText;
    private TextView dndStatusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Wire views
        batteryLevelText  = findViewById(R.id.batteryLevelText);
        statusText        = findViewById(R.id.statusText);
        dndStatusText     = findViewById(R.id.dndStatusText);
        puzzleEquationText = findViewById(R.id.puzzleEquationText);
        puzzleAnswerInput  = findViewById(R.id.puzzleAnswerInput);
        puzzleErrorText    = findViewById(R.id.puzzleErrorText);
        disableAlertsBtn   = findViewById(R.id.disableAlertsBtn);
        puzzleSection      = findViewById(R.id.puzzleSection);
        reenableSection    = findViewById(R.id.reenableSection);
        reenableAlertsBtn  = findViewById(R.id.reenableAlertsBtn);

        // Generate a fresh puzzle each time the app opens
        generatePuzzle();

        // Disable button — validate puzzle, then stop service
        disableAlertsBtn.setOnClickListener(v -> {
            String input = puzzleAnswerInput.getText().toString().trim();
            if (input.isEmpty()) {
                puzzleErrorText.setText("✗ Please enter an answer");
                puzzleErrorText.setVisibility(View.VISIBLE);
                return;
            }

            double userAnswer;
            try {
                userAnswer = Double.parseDouble(input);
            } catch (NumberFormatException e) {
                puzzleErrorText.setText("✗ Invalid number — enter e.g. 367.3");
                puzzleErrorText.setVisibility(View.VISIBLE);
                return;
            }

            // Compare to 1 decimal precision: truncate both to 1 decimal place
            double expected1dp = Math.floor(puzzleAnswer * 10) / 10.0;
            double user1dp     = Math.floor(userAnswer  * 10) / 10.0;

            if (Math.abs(user1dp - expected1dp) < 0.001) {
                // Correct! Disable alerts
                puzzleErrorText.setVisibility(View.GONE);
                hideKeyboard(v);
                prefs.edit().putBoolean(KEY_ENABLED, false).apply();
                stopBatteryService();
                updatePuzzleUI(false);
            } else {
                puzzleErrorText.setText("✗ Incorrect — try again");
                puzzleErrorText.setVisibility(View.VISIBLE);
                puzzleAnswerInput.selectAll();
            }
        });

        // Re-enable button — no puzzle needed
        reenableAlertsBtn.setOnClickListener(v -> {
            prefs.edit().putBoolean(KEY_ENABLED, true).apply();
            startBatteryService();
            // Regenerate puzzle for next disable attempt
            generatePuzzle();
            updatePuzzleUI(true);
        });

        // DND permission button
        findViewById(R.id.dndPermissionBtn).setOnClickListener(v -> requestDndPermission());

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }

        // Start service if enabled
        boolean isEnabled = prefs.getBoolean(KEY_ENABLED, true);
        if (isEnabled) {
            startBatteryService();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }

    // ─── Puzzle Generation ────────────────────────────────────────────────────

    private void generatePuzzle() {
        Random rng = new Random();
        // 3-4 digit numbers: range [1000, 9999]
        puzzleX = 1000 + rng.nextInt(9000);
        puzzleY = 1000 + rng.nextInt(9000);
        puzzleZ = 1000 + rng.nextInt(9000);
        puzzleAnswer = Math.sqrt((long) puzzleX * puzzleY + puzzleZ);

        String equation = "sqrt(" + puzzleX + " × " + puzzleY + " + " + puzzleZ + ") = ?";
        if (puzzleEquationText != null) {
            puzzleEquationText.setText(equation);
        }
    }

    // ─── UI state ─────────────────────────────────────────────────────────────

    private void updatePuzzleUI(boolean alertsEnabled) {
        if (alertsEnabled) {
            statusText.setText("● ACTIVE");
            statusText.setTextColor(getColor(R.color.green));
            puzzleSection.setVisibility(View.VISIBLE);
            reenableSection.setVisibility(View.GONE);
            puzzleAnswerInput.setText("");
            puzzleErrorText.setVisibility(View.GONE);
            // Show the current puzzle equation
            puzzleEquationText.setText(
                "sqrt(" + puzzleX + " × " + puzzleY + " + " + puzzleZ + ") = ?");
        } else {
            statusText.setText("● DISABLED");
            statusText.setTextColor(getColor(R.color.red));
            puzzleSection.setVisibility(View.GONE);
            reenableSection.setVisibility(View.VISIBLE);
        }
    }

    private void updateUI() {
        // Battery level
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int batteryPct = (int) ((level / (float) scale) * 100);
            batteryLevelText.setText(batteryPct + "%");
        }

        // DND permission
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        boolean hasDndAccess = nm != null && nm.isNotificationPolicyAccessGranted();
        if (hasDndAccess) {
            dndStatusText.setText("✓ Do Not Disturb Override: GRANTED");
            dndStatusText.setTextColor(getColor(R.color.green));
        } else {
            dndStatusText.setText("✗ Do Not Disturb Override: NOT GRANTED — Tap to grant");
            dndStatusText.setTextColor(getColor(R.color.red));
        }

        // Alerts enabled/disabled
        boolean isEnabled = prefs.getBoolean(KEY_ENABLED, true);
        updatePuzzleUI(isEnabled);
    }

    // ─── Service control ──────────────────────────────────────────────────────

    private void startBatteryService() {
        Intent serviceIntent = new Intent(this, BatteryMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void stopBatteryService() {
        Intent serviceIntent = new Intent(this, BatteryMonitorService.class);
        stopService(serviceIntent);
    }

    // ─── DND permission ───────────────────────────────────────────────────────

    private void requestDndPermission() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && !nm.isNotificationPolicyAccessGranted()) {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "Please grant Do Not Disturb access for Battery Alert", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "DND access already granted!", Toast.LENGTH_SHORT).show();
        }
    }

    // ─── Keyboard helper ─────────────────────────────────────────────────────

    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }
}
