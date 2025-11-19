package com.example.pomodorotimer;

import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.annotation.SuppressLint;

import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.NonNull;
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
    private ImageButton buttonBack;
    private FloatingActionButton buttonSettings;
    private CircularProgressIndicator circularProgress;

    // MediaPlayer used for custom audio notifications (user-provided files in res/raw)
    private MediaPlayer mediaPlayer;

    // Handler for delayed playback and pending runnable reference so it can be cancelled
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSoundRunnable;

    private CountDownTimer countDownTimer;
    private boolean isRunning = false;
    private boolean isWorkMode = true;

    // Flag to make sure we play the break-pre-end sound only once per break session
    private boolean breakPreEndPlayed = false;

    // Flag indicating we already pre-played the break-start sound during the last work session
    private boolean breakStartPrePlayed = false;

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

        // Back-gesture handler (modern replacement for deprecated onBackPressed())
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmExit();
            }
        });

        // Back button handler (UI button)
        if (buttonBack != null) {
            buttonBack.setOnClickListener(v -> confirmExit());
        }

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

    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_exit_title)
                .setMessage(R.string.confirm_exit_message)
                .setPositiveButton(R.string.yes, (dialog, which) -> {
                    // Reset sessions to 1 and persist
                    sessionsCompleted = 1;
                    savePreferences();
                    finish();
                })
                .setNegativeButton(R.string.no, (dialog, which) -> dialog.dismiss())
                .show();
    }

    // Restore view binding that was accidentally removed
    private void bindViews() {
        textMode = findViewById(R.id.text_mode);
        textTimer = findViewById(R.id.text_timer);
        textSessions = findViewById(R.id.text_sessions);
        buttonPlay = findViewById(R.id.button_play);
        buttonPause = findViewById(R.id.button_pause);
        buttonBack = findViewById(R.id.button_back);
        buttonSettings = findViewById(R.id.button_settings);
        circularProgress = findViewById(R.id.circular_progress);
    }

    // Restore notification channel creation
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

    // Play a short custom audio placed in res/raw by logical name.
    // Expected raw resource names (you should add these files):
    //   res/raw/work_start.mp3   -> "work_start"
    //   res/raw/work_end.mp3     -> "work_end"  (no longer used)
    //   res/raw/break_start.mp3  -> "break_start"
    //   res/raw/break_end.mp3    -> "break_end"
    // If the requested file is not present, falls back to the default notification sound.
    @SuppressLint("DiscouragedApi")
    private void playEventSound(String rawName) {
        try {
            stopAndReleaseMediaPlayer();
            int resId = getResources().getIdentifier(rawName, "raw", getPackageName());
            if (resId != 0) {
                mediaPlayer = MediaPlayer.create(this, resId);
                if (mediaPlayer != null) {
                    mediaPlayer.setOnCompletionListener(mp -> stopAndReleaseMediaPlayer());
                    mediaPlayer.start();
                    return;
                }
            }

            // Fallback: play default notification ringtone
            Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone r = RingtoneManager.getRingtone(this, soundUri);
            if (r != null) r.play();
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "playEventSound error", e);
        }
    }

    // Play an event sound after a specified delay (milliseconds). Cancels any previously scheduled sound.
    @SuppressWarnings("unused")
    private void playEventSoundDelayed(String rawName, long delayMs) {
        try {
            if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
            // Cancel previous pending sound if any
            if (pendingSoundRunnable != null) {
                mainHandler.removeCallbacks(pendingSoundRunnable);
                // pendingSoundRunnable = null; // redundant — we'll overwrite below
            }
            pendingSoundRunnable = () -> playEventSound(rawName);
            mainHandler.postDelayed(pendingSoundRunnable, delayMs);
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "playEventSoundDelayed error", e);
        }
    }

    private void stopAndReleaseMediaPlayer() {
        try {
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.reset();
                mediaPlayer.release();
                mediaPlayer = null;
            }
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "stopAndReleaseMediaPlayer error", e);
            mediaPlayer = null;
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
            } catch (Exception e) {
                android.util.Log.w("MainActivity", "Failed to play notification sound", e);
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
        sessionsCompleted = prefs.getInt(KEY_SESSIONS, 1); // default to 1
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

        // Play start sound for the current mode immediately (no general delay).
        // If we pre-played the break-start sound during the prior work session, consume the flag and skip double-play.
        if (isWorkMode) {
            // Starting a work session: play work_start and reset the pre-play flag so it may trigger again later
            playEventSound("work_start");
            breakStartPrePlayed = false; // ensure pre-play will be available during this work session
        } else {
            // Starting a break session: if break_start was already pre-played during the previous work session, consume it and do not replay.
            if (breakStartPrePlayed) {
                breakStartPrePlayed = false; // consumed; do not play again
            } else {
                playEventSound("break_start");
            }
        }

        // Reset break pre-end flag when a new session starts
        breakPreEndPlayed = false;

        long sessionTotal = isWorkMode ? workMillis : breakMillis;

        // keep last progress to animate smoothly
        final int[] lastProgress = {circularProgress.getProgress()};

        countDownTimer = new CountDownTimer(timeLeft, 250) {
            @Override
            public void onTick(long millisUntilFinished) {
                timeLeft = millisUntilFinished;
                updateTimerText();

                // If we're in a work session, pre-play the break_start sound 1.5 seconds before the work session ends (once)
                if (isWorkMode && !breakStartPrePlayed && millisUntilFinished <= 1500) {
                    try {
                        playEventSound("break_start");
                        breakStartPrePlayed = true;
                    } catch (Exception e) {
                        android.util.Log.w("MainActivity", "Error pre-playing break_start", e);
                    }
                }

                // If we're in a break session, play the break_end sound when 2 seconds remain (once)
                if (!isWorkMode && !breakPreEndPlayed && millisUntilFinished <= 2000) {
                    try {
                        // play immediately (2 seconds before end)
                        playEventSound("break_end");
                        breakPreEndPlayed = true;
                    } catch (Exception e) {
                        android.util.Log.w("MainActivity", "Error playing break_end pre-sound", e);
                    }
                }

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
                // When a session finishes we switch modes and auto-start the next session.
                // The next session's start sound is played by startTimer() when it begins.

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
                // auto-start next (startTimer will play the start sound immediately)
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
        // Cancel any pending sound callbacks
        if (mainHandler != null && pendingSoundRunnable != null) {
            mainHandler.removeCallbacks(pendingSoundRunnable);
            pendingSoundRunnable = null;
        }
        // reset break pre-end flag
        breakPreEndPlayed = false;
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
            Vibrator v = getSystemService(Vibrator.class);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(300);
                }
            }
        } catch (SecurityException ignored) {
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
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
        // Reset sessions to 1 whenever user leaves the app
        sessionsCompleted = 1;
        savePreferences();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        // Cancel pending sound callbacks and release player
        if (mainHandler != null && pendingSoundRunnable != null) {
            mainHandler.removeCallbacks(pendingSoundRunnable);
            pendingSoundRunnable = null;
        }
        stopAndReleaseMediaPlayer();
    }
}
