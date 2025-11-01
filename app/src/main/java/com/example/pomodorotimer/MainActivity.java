package com.example.pomodorotimer;

import android.animation.ObjectAnimator;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Vibrator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;

public class MainActivity extends AppCompatActivity {

    private static final String CHANNEL_ID = "pomodoro_channel";

    private TextView textMode;
    private TextView textTimer;
    private TextView textSessions; // new sessions counter
    private ImageButton buttonPlay;
    private ImageButton buttonPause;
    private FloatingActionButton buttonSettings;
    private CircularProgressIndicator circularProgress;

    private CountDownTimer countDownTimer;
    private boolean isRunning = false;
    private boolean isWorkMode = true;

    private long workMillis = 25 * 60 * 1000L;
    private long breakMillis = 5 * 60 * 1000L;
    private long timeLeft = workMillis; // milliseconds remaining

    private int sessionsCompleted = 0; // persisted count

    private static final String PREFS = "pomodoro_prefs";
    private static final String KEY_WORK = "work_minutes";
    private static final String KEY_BREAK = "break_minutes";
    private static final String KEY_IS_WORK = "is_work";
    private static final String KEY_TIME_LEFT = "time_left";
    private static final String KEY_IS_RUNNING = "is_running";
    private static final String KEY_SESSIONS = "sessions_completed";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        bindViews();
        createNotificationChannel();
        loadPreferences();

        if (savedInstanceState != null) {
            isWorkMode = savedInstanceState.getBoolean(KEY_IS_WORK, true);
            timeLeft = savedInstanceState.getLong(KEY_TIME_LEFT, isWorkMode ? workMillis : breakMillis);
            isRunning = savedInstanceState.getBoolean(KEY_IS_RUNNING, false);
            sessionsCompleted = savedInstanceState.getInt(KEY_SESSIONS, sessionsCompleted);
        } else {
            timeLeft = isWorkMode ? workMillis : breakMillis;
        }

        updateUi();

        buttonPlay.setOnClickListener(v -> startTimer());
        buttonPause.setOnClickListener(v -> stopTimer());
        buttonSettings.setOnClickListener(v -> showSettingsSheet());

        if (isRunning) startTimer();
    }

    private void bindViews() {
        textMode = findViewById(R.id.text_mode);
        textTimer = findViewById(R.id.text_timer);
        textSessions = findViewById(R.id.text_sessions);
        buttonPlay = findViewById(R.id.button_play);
        buttonPause = findViewById(R.id.button_pause);
        buttonSettings = findViewById(R.id.button_settings);
        circularProgress = findViewById(R.id.circular_progress);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Pomodoro Alerts";
            String description = "Notifications for Pomodoro session events";
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) notificationManager.createNotificationChannel(channel);
        }
    }

    private void showNotification(String title, String text) {
        // Require POST_NOTIFICATIONS on Android 13+ and check notifications are enabled
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    android.util.Log.w("MainActivity", "POST_NOTIFICATIONS permission not granted; skipping notification");
                    return;
                }
            }

            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
            if (!notificationManager.areNotificationsEnabled()) {
                android.util.Log.w("MainActivity", "Notifications are disabled by user; skipping notification");
                return;
            }

            Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setSound(soundUri)
                    .setAutoCancel(true);

            try {
                notificationManager.notify((int) System.currentTimeMillis(), builder.build());
            } catch (SecurityException se) {
                android.util.Log.w("MainActivity", "Failed to post notification", se);
                return;
            }

            // Also play the ringtone immediately (some devices delay notification sound)
            try {
                Ringtone r = RingtoneManager.getRingtone(this, soundUri);
                if (r != null) r.play();
            } catch (Exception ignored) {
                android.util.Log.w("MainActivity", "Failed to play notification sound", ignored);
            }
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "showNotification error", e);
        }
    }

    private void showSettingsSheet() {
        try {
            BottomSheetDialog dialog = new BottomSheetDialog(this);
            dialog.setContentView(R.layout.bottom_sheet_settings);

            final EditText bsInputWork = dialog.findViewById(R.id.bs_input_work);
            final EditText bsInputBreak = dialog.findViewById(R.id.bs_input_break);
            Button bsApply = dialog.findViewById(R.id.bs_apply);

            if (bsInputWork != null) bsInputWork.setText(String.valueOf(workMillis / 60000L));
            if (bsInputBreak != null) bsInputBreak.setText(String.valueOf(breakMillis / 60000L));

            if (bsApply != null) {
                bsApply.setOnClickListener(v -> {
                    try {
                        // guard against null EditText references or null text
                        if (bsInputWork == null || bsInputBreak == null) {
                            dialog.dismiss();
                            return;
                        }
                        CharSequence wCs = bsInputWork.getText();
                        CharSequence bCs = bsInputBreak.getText();
                        String w = wCs == null ? "" : wCs.toString().trim();
                        String b = bCs == null ? "" : bCs.toString().trim();
                        int wMin = 25;
                        int bMin = 5;
                        if (!w.isEmpty()) wMin = Integer.parseInt(w);
                        if (!b.isEmpty()) bMin = Integer.parseInt(b);
                        if (wMin <= 0) wMin = 1;
                        if (bMin <= 0) bMin = 1;
                        workMillis = wMin * 60 * 1000L;
                        breakMillis = bMin * 60 * 1000L;
                        timeLeft = isWorkMode ? workMillis : breakMillis;
                        savePreferences();
                        updateUi();
                    } catch (Exception ex) {
                        android.util.Log.w("MainActivity", "Error parsing settings input", ex);
                    }
                    dialog.dismiss();
                });
            }

            dialog.show();
        } catch (Exception ex) {
            // Catch unexpected inflation errors to avoid app crash; fallback to a simple dialog
            android.util.Log.e("MainActivity", "Failed to show settings sheet", ex);
        }
    }

    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int workMin = prefs.getInt(KEY_WORK, 25);
        int breakMin = prefs.getInt(KEY_BREAK, 5);
        isWorkMode = prefs.getBoolean(KEY_IS_WORK, true);
        workMillis = workMin * 60 * 1000L;
        breakMillis = breakMin * 60 * 1000L;
        sessionsCompleted = prefs.getInt(KEY_SESSIONS, 0);
        if (textSessions != null) textSessions.setText(getString(R.string.sessions_label, sessionsCompleted));
    }

    private void savePreferences() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        editor.putInt(KEY_WORK, (int) (workMillis / 60000L));
        editor.putInt(KEY_BREAK, (int) (breakMillis / 60000L));
        editor.putBoolean(KEY_IS_WORK, isWorkMode);
        editor.putInt(KEY_SESSIONS, sessionsCompleted);
        editor.apply();
    }

    private void startTimer() {
        if (isRunning) return;
        isRunning = true;
        buttonPlay.setEnabled(false);
        buttonPause.setEnabled(true);

        long sessionTotal = isWorkMode ? workMillis : breakMillis;

        // keep last progress to animate smoothly
        final int[] lastProgress = {circularProgress.getProgress()};

        countDownTimer = new CountDownTimer(timeLeft, 250) {
            @Override
            public void onTick(long millisUntilFinished) {
                timeLeft = millisUntilFinished;
                updateTimerText();
                float fraction = (float) (sessionTotal - timeLeft) / (float) sessionTotal;
                int newProgress = Math.max(0, Math.min(100, Math.round(fraction * 100)));
                // animate progress from lastProgress to newProgress
                ObjectAnimator anim = ObjectAnimator.ofInt(circularProgress, "progress", lastProgress[0], newProgress);
                anim.setDuration(220);
                anim.start();
                lastProgress[0] = newProgress;
            }

            @Override
            public void onFinish() {
                timeLeft = 0;
                updateTimerText();
                ObjectAnimator.ofInt(circularProgress, "progress", 100).setDuration(200).start();
                // feedback
                vibrateOnFinish();
                showNotification(isWorkMode ? "Work complete" : "Break complete", isWorkMode ? "Time for a break" : "Back to work");
                // If a work session just completed, increment sessions
                if (isWorkMode) {
                    sessionsCompleted++;
                    savePreferences();
                    animateSessions();
                }
                // Switch mode
                isWorkMode = !isWorkMode;
                // subtle mode-change animation (use new mode value so label matches)
                animateModeChange();
                timeLeft = isWorkMode ? workMillis : breakMillis;
                isRunning = false;
                updateUi();
                // auto-start next
                startTimer();
            }
        };

        // initial smooth progress
        float fractionInit = (float) (((isWorkMode ? workMillis : breakMillis) - timeLeft)) / (float) (isWorkMode ? workMillis : breakMillis);
        int initProgress = Math.max(0, Math.min(100, Math.round(fractionInit * 100)));
        ObjectAnimator.ofInt(circularProgress, "progress", circularProgress.getProgress(), initProgress).setDuration(200).start();
        countDownTimer.start();
    }

    private void stopTimer() {
        if (!isRunning) return;
        isRunning = false;
        buttonPlay.setEnabled(true);
        buttonPause.setEnabled(false);
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        updateUi();
    }

    private void animateModeChange() {
        // subtle scale+fade animation on the timer and mode label
        textTimer.animate().scaleX(0.92f).scaleY(0.92f).alpha(0.6f).setDuration(180).withEndAction(() -> {
            // restore
            textTimer.setScaleX(1f);
            textTimer.setScaleY(1f);
            textTimer.setAlpha(1f);
            textMode.setAlpha(0f);
            textMode.setText(isWorkMode ? getString(R.string.mode_work) : getString(R.string.mode_break));
            textMode.animate().alpha(1f).setDuration(220).start();
        }).start();
    }

    private void animateSessions() {
        if (textSessions == null) return;
        textSessions.setText(getString(R.string.sessions_label, sessionsCompleted));
        textSessions.animate().scaleX(1.16f).scaleY(1.16f).setDuration(160).withEndAction(() -> textSessions.animate().scaleX(1f).scaleY(1f).setDuration(160).start()).start();
    }

    private void updateUi() {
        textMode.setText(isWorkMode ? getString(R.string.mode_work) : getString(R.string.mode_break));
        updateTimerText();
        buttonPlay.setEnabled(!isRunning);
        buttonPause.setEnabled(isRunning);
        if (textSessions != null) textSessions.setText(getString(R.string.sessions_label, sessionsCompleted));
    }

    private void updateTimerText() {
        long seconds = (timeLeft / 1000) % 60;
        long minutes = (timeLeft / 1000) / 60;
        String time = String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds);
        textTimer.setText(time);
    }

    private void vibrateOnFinish() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) v.vibrate(300);
        } catch (SecurityException ignored) {
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_IS_WORK, isWorkMode);
        outState.putLong(KEY_TIME_LEFT, timeLeft);
        outState.putBoolean(KEY_IS_RUNNING, isRunning);
        outState.putInt(KEY_SESSIONS, sessionsCompleted);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
    }
}
