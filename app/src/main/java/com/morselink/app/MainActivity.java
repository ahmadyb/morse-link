package com.morselink.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
import android.widget.ViewFlipper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** The one and only screen: four tabs (Codec, Send, Receive, Learn). */
public class MainActivity extends Activity implements Transmitter.Callback {

    private static final int REQ_CAMERA = 101;
    private static final int REQ_AUDIO = 102;

    private static final int TAB_CODEC = 0;
    private static final int TAB_SEND = 1;
    private static final int TAB_RECEIVE = 2;
    private static final int TAB_LEARN = 3;

    private static final int MIN_WPM = 5;
    private static final int MIN_TONE = 300;
    private static final int MIN_RX_FREQ = 400;

    private Prefs prefs;
    private Transmitter transmitter;
    private Receiver receiver;

    private ViewFlipper flipper;
    private View flashOverlay;
    private Button[] navButtons;

    // Codec tab
    private EditText codecText;
    private EditText codecMorse;
    private boolean syncing;

    // Send tab
    private EditText sendMessage;
    private ToggleButton chTorch;
    private ToggleButton chSound;
    private ToggleButton chScreen;
    private ToggleButton chVibrate;
    private SeekBar speedBar;
    private SeekBar toneBar;
    private TextView speedValue;
    private TextView toneValue;
    private TextView sendStatus;
    private TextView sendPreview;
    private Button sendStart;
    private Button sendStop;
    private CheckBox sendLoop;
    private String previewMorse = "";

    // Receive tab
    private Button recvStart;
    private Button recvStop;
    private SeekBar recvFreqBar;
    private SeekBar recvGainBar;
    private TextView recvFreqValue;
    private TextView recvGainValue;
    private TextView recvMorse;
    private TextView recvText;
    private TextView recvStatus;
    private ProgressBar recvLevel;

    // Learn tab
    private ViewFlipper learnFlipper;
    private ToggleButton learnTabChart;
    private ToggleButton learnTabQuiz;
    private GridLayout chartGrid;
    private GridLayout quizOptions;
    private TextView quizScore;
    private TextView quizFeedback;
    private char quizAnswer;
    private int quizStreak;
    private int quizBest;

    private boolean pendingTransmit;
    private boolean pendingListen;
    private boolean screenWasUsed;

    private final Random random = new Random();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);
        transmitter = new Transmitter(this, this);
        receiver = new Receiver(new RxCallback());

        bindNavigation();
        bindCodec(getLayoutInflater().inflate(R.layout.view_codec, flipper, false));
        bindSend(getLayoutInflater().inflate(R.layout.view_transmit, flipper, false));
        bindReceive(getLayoutInflater().inflate(R.layout.view_receive, flipper, false));
        bindLearn(getLayoutInflater().inflate(R.layout.view_learn, flipper, false));

        ImageButton about = findViewById(R.id.btn_about);
        about.setOnClickListener(v -> showAbout());

        restoreState();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    // ---------------------------------------------------------------- navigation

    private void bindNavigation() {
        flipper = findViewById(R.id.flipper);
        flashOverlay = findViewById(R.id.flash_overlay);
        navButtons = new Button[]{
                findViewById(R.id.nav_codec),
                findViewById(R.id.nav_send),
                findViewById(R.id.nav_receive),
                findViewById(R.id.nav_learn)
        };
        for (int i = 0; i < navButtons.length; i++) {
            final int index = i;
            navButtons[i].setOnClickListener(v -> showTab(index));
        }
    }

    private void showTab(int index) {
        if (flipper.getDisplayedChild() != index) {
            flipper.setDisplayedChild(index);
        }
        for (int i = 0; i < navButtons.length; i++) {
            navButtons[i].setSelected(i == index);
        }
        prefs.tab(index);
        if (index == TAB_LEARN && quizOptions.getChildCount() == 0) {
            nextQuestion();
        }
    }

    // -------------------------------------------------------------------- codec

    private void bindCodec(View root) {
        codecText = root.findViewById(R.id.codec_text);
        codecMorse = root.findViewById(R.id.codec_morse);
        Button copyText = root.findViewById(R.id.codec_copy_text);
        Button copyMorse = root.findViewById(R.id.codec_copy_morse);
        Button share = root.findViewById(R.id.codec_share);
        Button clear = root.findViewById(R.id.codec_clear);
        Button transmit = root.findViewById(R.id.codec_transmit);
        final Button stop = root.findViewById(R.id.codec_stop);
        Button sos = root.findViewById(R.id.codec_sos);

        codecText.addTextChangedListener((SimpleWatcher) () -> {
            if (syncing) {
                return;
            }
            syncing = true;
            codecMorse.setText(MorseCodec.pretty(MorseCodec.encode(codecText.getText())));
            syncing = false;
        });
        codecMorse.addTextChangedListener((SimpleWatcher) () -> {
            if (syncing) {
                return;
            }
            syncing = true;
            codecText.setText(MorseCodec.decode(codecMorse.getText()));
            syncing = false;
        });

        copyText.setOnClickListener(v -> copyToClipboard("text",
                codecText.getText().toString().toUpperCase()));
        copyMorse.setOnClickListener(v -> copyToClipboard("morse",
                MorseCodec.pretty(MorseCodec.encode(codecText.getText()))));
        share.setOnClickListener(v -> shareText(codecText.getText().toString().trim()));
        clear.setOnClickListener(v -> {
            codecText.setText("");
            codecMorse.setText("");
        });
        transmit.setOnClickListener(v -> {
            String message = codecText.getText().toString().trim();
            if (message.isEmpty()) {
                toast(getString(R.string.empty_message));
                return;
            }
            showTab(TAB_SEND);
            sendMessage.setText(message);
            startTransmit();
        });
        stop.setOnClickListener(v -> stopTransmit());
        sos.setOnClickListener(v -> {
            codecText.setText("SOS");
            showTab(TAB_SEND);
            sendMessage.setText("SOS");
            startTransmit();
        });

        flipper.addView(root);
    }

    // --------------------------------------------------------------------- send

    private void bindSend(View root) {
        sendMessage = root.findViewById(R.id.send_message);
        chTorch = root.findViewById(R.id.ch_torch);
        chSound = root.findViewById(R.id.ch_sound);
        chScreen = root.findViewById(R.id.ch_screen);
        chVibrate = root.findViewById(R.id.ch_vibrate);
        speedBar = root.findViewById(R.id.send_speed_bar);
        toneBar = root.findViewById(R.id.send_tone_bar);
        speedValue = root.findViewById(R.id.send_speed_value);
        toneValue = root.findViewById(R.id.send_tone_value);
        sendStatus = root.findViewById(R.id.send_status);
        sendPreview = root.findViewById(R.id.send_morse_preview);
        sendStart = root.findViewById(R.id.send_start);
        sendStop = root.findViewById(R.id.send_stop);
        sendLoop = root.findViewById(R.id.send_loop);

        speedBar.setOnSeekBarChangeListener((SimpleSeek) progress -> {
            int wpm = MIN_WPM + progress;
            speedValue.setText(getString(R.string.wpm_value, wpm));
            prefs.wpm(wpm);
        });
        toneBar.setOnSeekBarChangeListener((SimpleSeek) progress -> {
            int hz = MIN_TONE + progress;
            toneValue.setText(getString(R.string.hz_value, hz));
            prefs.tone(hz);
        });

        ToggleButton[] channels = {chTorch, chSound, chScreen, chVibrate};
        for (ToggleButton channel : channels) {
            channel.setOnCheckedChangeListener((buttonView, isChecked) -> prefs.channels(channelMask()));
        }
        sendLoop.setOnCheckedChangeListener((buttonView, isChecked) -> prefs.loop(isChecked));
        sendMessage.addTextChangedListener((SimpleWatcher) () ->
                prefs.message(sendMessage.getText().toString()));

        sendStart.setOnClickListener(v -> startTransmit());
        sendStop.setOnClickListener(v -> stopTransmit());
        ((Button) root.findViewById(R.id.send_sos)).setOnClickListener(v -> {
            sendMessage.setText("SOS");
            startTransmit();
        });
        ((Button) root.findViewById(R.id.send_clear)).setOnClickListener(v -> {
            sendMessage.setText("");
            sendPreview.setText("");
            sendStatus.setText(R.string.send_status_idle);
        });

        flipper.addView(root);
    }

    private int channelMask() {
        int mask = 0;
        if (chTorch.isChecked()) {
            mask |= Transmitter.CH_TORCH;
        }
        if (chSound.isChecked()) {
            mask |= Transmitter.CH_SOUND;
        }
        if (chScreen.isChecked()) {
            mask |= Transmitter.CH_SCREEN;
        }
        if (chVibrate.isChecked()) {
            mask |= Transmitter.CH_VIBRATE;
        }
        return mask;
    }

    private int wpm() {
        return MIN_WPM + speedBar.getProgress();
    }

    private int toneHz() {
        return MIN_TONE + toneBar.getProgress();
    }

    private void startTransmit() {
        String message = sendMessage.getText() == null ? "" : sendMessage.getText().toString().trim();
        if (message.isEmpty()) {
            toast(getString(R.string.empty_message));
            return;
        }
        int mask = channelMask();
        if (mask == 0) {
            toast(getString(R.string.pick_output));
            return;
        }
        if ((mask & Transmitter.CH_TORCH) != 0 && !hasPermission(android.Manifest.permission.CAMERA)) {
            pendingTransmit = true;
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        pendingTransmit = false;
        if ((mask & Transmitter.CH_SCREEN) != 0) {
            screenWasUsed = true;
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = 1.0f;
            getWindow().setAttributes(lp);
            flashOverlay.setBackgroundColor(Color.BLACK);
            flashOverlay.setVisibility(View.VISIBLE);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setTransmitting(true);
        transmitter.configure(mask, wpm(), toneHz(), sendLoop.isChecked());
        transmitter.start(message);
    }

    private void stopTransmit() {
        pendingTransmit = false;
        transmitter.stop();
        setTransmitting(false);
        clearFlash();
    }

    private void setTransmitting(boolean active) {
        sendStart.setEnabled(!active);
        sendStop.setEnabled(active);
        sendStatus.setText(active ? "Transmitting…" : getString(R.string.send_status_idle));
        if (!active) {
            sendPreview.setText(previewMorse == null ? "" : MorseCodec.pretty(previewMorse));
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void clearFlash() {
        flashOverlay.setVisibility(View.GONE);
        if (screenWasUsed) {
            screenWasUsed = false;
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = -1.0f;
            getWindow().setAttributes(lp);
        }
    }

    // --------------------------------------------------------- Transmitter.Callback

    @Override
    public void onStarted(String morse, long totalMs, boolean truncated) {
        previewMorse = morse;
        sendPreview.setText(MorseCodec.pretty(morse));
        sendStatus.setText("Transmitting " + (totalMs / 1000L) + "s of Morse…");
        if (truncated) {
            toast(getString(R.string.message_trimmed));
        }
    }

    @Override
    public void onTick(int morseIndex, boolean on) {
        if ((channelMask() & Transmitter.CH_SCREEN) != 0) {
            flashOverlay.setVisibility(View.VISIBLE);
            flashOverlay.setBackgroundColor(on ? getColor(R.color.flash_on) : Color.BLACK);
        }
        if (previewMorse == null || previewMorse.isEmpty()) {
            return;
        }
        SpannableString span = new SpannableString(MorseCodec.pretty(previewMorse));
        if (on && morseIndex >= 0 && morseIndex < span.length()) {
            span.setSpan(new android.text.style.BackgroundColorSpan(getColor(R.color.accent_dim)),
                    morseIndex, morseIndex + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sendPreview.setText(span);
    }

    @Override
    public void onStopped() {
        setTransmitting(false);
        clearFlash();
    }

    @Override
    public void onError(String message) {
        if ("no_torch".equals(message)) {
            toast(getString(R.string.no_torch));
            chTorch.setChecked(false);
            prefs.channels(channelMask());
            return;
        }
        if (message != null && !message.isEmpty()) {
            toast(getString(R.string.torch_unavailable, message));
        }
    }

    // ------------------------------------------------------------------ receive

    private void bindReceive(View root) {
        recvStart = root.findViewById(R.id.recv_start);
        recvStop = root.findViewById(R.id.recv_stop);
        recvFreqBar = root.findViewById(R.id.recv_freq_bar);
        recvGainBar = root.findViewById(R.id.recv_gain_bar);
        recvFreqValue = root.findViewById(R.id.recv_freq_value);
        recvGainValue = root.findViewById(R.id.recv_gain_value);
        recvMorse = root.findViewById(R.id.recv_morse);
        recvText = root.findViewById(R.id.recv_text);
        recvStatus = root.findViewById(R.id.recv_status);
        recvLevel = root.findViewById(R.id.recv_level);

        recvFreqBar.setOnSeekBarChangeListener((SimpleSeek) progress -> {
            int hz = MIN_RX_FREQ + progress;
            recvFreqValue.setText(getString(R.string.hz_value, hz));
            prefs.recvFreq(hz);
        });
        recvGainBar.setOnSeekBarChangeListener((SimpleSeek) progress -> {
            recvGainValue.setText(progress + "%");
            prefs.recvGain(progress);
        });

        recvStart.setOnClickListener(v -> startListening());
        recvStop.setOnClickListener(v -> stopListening());
        ((Button) root.findViewById(R.id.recv_copy)).setOnClickListener(v ->
                copyToClipboard("decoded", recvText.getText().toString()));
        ((Button) root.findViewById(R.id.recv_clear)).setOnClickListener(v -> {
            recvMorse.setText("");
            recvText.setText("");
        });

        flipper.addView(root);
    }

    private void startListening() {
        if (!hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
            pendingListen = true;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        pendingListen = false;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        receiver.start(MIN_RX_FREQ + recvFreqBar.getProgress(), recvGainBar.getProgress());
        recvStart.setEnabled(false);
        recvStop.setEnabled(true);
        recvStatus.setText("Listening…");
    }

    private void stopListening() {
        pendingListen = false;
        receiver.stop();
        recvStart.setEnabled(true);
        recvStop.setEnabled(false);
        recvStatus.setText(R.string.receive_idle);
        recvLevel.setProgress(0);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // -------------------------------------------------------------------- learn

    private void bindLearn(View root) {
        learnFlipper = root.findViewById(R.id.learn_flipper);
        learnTabChart = root.findViewById(R.id.learn_tab_chart);
        learnTabQuiz = root.findViewById(R.id.learn_tab_quiz);
        chartGrid = root.findViewById(R.id.learn_chart_grid);
        quizOptions = root.findViewById(R.id.quiz_options);
        quizScore = root.findViewById(R.id.quiz_score);
        quizFeedback = root.findViewById(R.id.quiz_feedback);

        learnTabChart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            learnTabQuiz.setChecked(!isChecked);
            learnFlipper.setDisplayedChild(isChecked ? 0 : 1);
        });
        learnTabQuiz.setOnCheckedChangeListener((buttonView, isChecked) -> {
            learnTabChart.setChecked(!isChecked);
            learnFlipper.setDisplayedChild(isChecked ? 1 : 0);
        });

        buildChart();
        quizBest = prefs.bestStreak();
        ((Button) root.findViewById(R.id.quiz_replay)).setOnClickListener(v -> playQuizTone());
        ((Button) root.findViewById(R.id.quiz_next)).setOnClickListener(v -> nextQuestion());
        updateScore();

        flipper.addView(root);
    }

    private void buildChart() {
        int padding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10,
                getResources().getDisplayMetrics());
        for (final MorseCodec.Entry entry : MorseCodec.entries()) {
            TextView cell = new TextView(this);
            GridLayout.LayoutParams cellParams = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED));
            cellParams.width = 0;
            cell.setLayoutParams(cellParams);
            cell.setBackgroundResource(R.drawable.bg_button_surface);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(padding, padding, padding, padding);
            cell.setMinHeight((int) (padding * 5.6f));

            SpannableStringBuilder label = new SpannableStringBuilder();
            label.append(String.valueOf(entry.character));
            label.setSpan(new RelativeSizeSpan(1.25f), 0, label.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.append("\n");
            int codeStart = label.length();
            label.append(MorseCodec.pretty(entry.code));
            label.setSpan(new ForegroundColorSpan(getColor(R.color.accent)), codeStart, label.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setSpan(new RelativeSizeSpan(0.95f), codeStart, label.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            cell.setText(label);
            cell.setOnClickListener(v -> playCharacter(entry.character));
            chartGrid.addView(cell);
        }
    }

    private void playCharacter(char c) {
        int mask = Transmitter.CH_SOUND;
        if (hasPermission(android.Manifest.permission.CAMERA)) {
            mask |= Transmitter.CH_TORCH;
        }
        transmitter.configure(mask, Math.max(8, wpm()), toneHz(), false);
        transmitter.start(String.valueOf(c));
    }

    private void nextQuestion() {
        List<Character> pool = new ArrayList<>();
        for (MorseCodec.Entry entry : MorseCodec.entries()) {
            if (Character.isLetterOrDigit(entry.character)) {
                pool.add(entry.character);
            }
        }
        Collections.shuffle(pool, random);
        quizAnswer = pool.get(0);
        List<Character> options = new ArrayList<>(pool.subList(0, 4));
        Collections.shuffle(options, random);

        quizOptions.removeAllViews();
        for (final Character option : options) {
            Button button = new Button(this);
            GridLayout.LayoutParams buttonParams = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED));
            buttonParams.width = 0;
            button.setLayoutParams(buttonParams);
            button.setBackgroundResource(R.drawable.bg_button_surface);
            button.setTextColor(getColor(R.color.text_primary));
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            button.setMinHeight((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 56,
                    getResources().getDisplayMetrics()));
            button.setAllCaps(false);
            button.setText(String.valueOf(option.charValue()));
            button.setOnClickListener(v -> answer(option.charValue(), button));
            quizOptions.addView(button);
        }
        quizFeedback.setText("");
        // Only play straight away when the quiz is actually on screen, so opening
        // the app on a remembered tab never beeps out of nowhere.
        if (flipper.getDisplayedChild() == TAB_LEARN && learnFlipper.getDisplayedChild() == 1) {
            playQuizTone();
        }
    }

    private void answer(char choice, Button button) {
        boolean correct = choice == quizAnswer;
        button.setBackgroundResource(correct ? R.drawable.bg_button_primary : R.drawable.bg_button_danger);
        for (int i = 0; i < quizOptions.getChildCount(); i++) {
            quizOptions.getChildAt(i).setEnabled(false);
        }
        if (correct) {
            quizStreak++;
            if (quizStreak > quizBest) {
                quizBest = quizStreak;
                prefs.bestStreak(quizBest);
            }
            quizFeedback.setText(getString(R.string.quiz_correct));
            quizFeedback.setTextColor(getColor(R.color.success));
        } else {
            quizStreak = 0;
            quizFeedback.setText(getString(R.string.quiz_wrong, String.valueOf(quizAnswer)));
            quizFeedback.setTextColor(getColor(R.color.danger));
            for (int i = 0; i < quizOptions.getChildCount(); i++) {
                View child = quizOptions.getChildAt(i);
                if (child instanceof Button
                        && ((Button) child).getText().toString().equals(String.valueOf(quizAnswer))) {
                    child.setBackgroundResource(R.drawable.bg_button_primary);
                }
            }
        }
        updateScore();
    }

    private void playQuizTone() {
        int mask = Transmitter.CH_SOUND;
        if (hasPermission(android.Manifest.permission.CAMERA)) {
            mask |= Transmitter.CH_TORCH;
        }
        transmitter.configure(mask, Math.max(8, wpm()), toneHz(), false);
        transmitter.start(String.valueOf(quizAnswer));
    }

    private void updateScore() {
        quizScore.setText(getString(R.string.quiz_score, quizStreak, quizBest));
    }

    // ------------------------------------------------------------------- misc ui

    private void restoreState() {
        speedBar.setProgress(Math.max(0, prefs.wpm() - MIN_WPM));
        speedValue.setText(getString(R.string.wpm_value, prefs.wpm()));
        toneBar.setProgress(Math.max(0, prefs.tone() - MIN_TONE));
        toneValue.setText(getString(R.string.hz_value, prefs.tone()));
        recvFreqBar.setProgress(Math.max(0, prefs.recvFreq() - MIN_RX_FREQ));
        recvFreqValue.setText(getString(R.string.hz_value, prefs.recvFreq()));
        recvGainBar.setProgress(prefs.recvGain());
        recvGainValue.setText(prefs.recvGain() + "%");
        sendLoop.setChecked(prefs.loop());

        int channels = prefs.channels();
        chTorch.setChecked((channels & Transmitter.CH_TORCH) != 0 && transmitter.torchAvailable());
        chSound.setChecked((channels & Transmitter.CH_SOUND) != 0);
        chScreen.setChecked((channels & Transmitter.CH_SCREEN) != 0);
        chVibrate.setChecked((channels & Transmitter.CH_VIBRATE) != 0);

        String saved = prefs.message();
        if (saved != null && !saved.isEmpty()) {
            sendMessage.setText(saved);
            sendMessage.setSelection(saved.length());
        } else {
            sendMessage.setText("MORSE LINK");
        }
        showTab(Math.max(0, Math.min(3, prefs.tab())));
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (shared != null && !shared.trim().isEmpty()) {
            codecText.setText(shared.trim());
            showTab(TAB_CODEC);
        }
    }

    private void showAbout() {
        String version = "1.0";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // keep the fallback
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_message, version))
                .setPositiveButton(R.string.about_ok, null)
                .show();
    }

    private void copyToClipboard(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        toast(getString(R.string.copied));
    }

    private void shareText(String value) {
        if (value.isEmpty()) {
            toast(getString(R.string.empty_message));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, value + "\n" + MorseCodec.pretty(MorseCodec.encode(value)));
        startActivity(Intent.createChooser(intent, getString(R.string.app_name)));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_CAMERA) {
            if (granted && pendingTransmit) {
                startTransmit();
            } else if (!granted) {
                toast(getString(R.string.perm_camera_needed));
                chTorch.setChecked(false);
            }
            pendingTransmit = false;
        } else if (requestCode == REQ_AUDIO) {
            if (granted && pendingListen) {
                startListening();
            } else if (!granted) {
                toast(getString(R.string.perm_audio_needed));
            }
            pendingListen = false;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiver.isRunning()) {
            stopListening();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        transmitter.stop();
        receiver.stop();
    }

    // ----------------------------------------------------------------- plumbing

    /** TextWatcher with only the callback we care about, so call sites can use a lambda. */
    private interface SimpleWatcher extends TextWatcher {
        void onChanged();

        default void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        default void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        default void afterTextChanged(android.text.Editable s) {
            onChanged();
        }
    }

    /** SeekBar listener with only the callback we care about. */
    private interface SimpleSeek extends SeekBar.OnSeekBarChangeListener {
        void onChanged(int progress);

        default void onStartTrackingTouch(SeekBar seekBar) {
        }

        default void onStopTrackingTouch(SeekBar seekBar) {
        }

        default void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            onChanged(progress);
        }
    }

    /** Microphone callbacks, kept separate from the transmitter's. */
    private final class RxCallback implements Receiver.Callback {
        @Override
        public void onLevel(int percent) {
            recvLevel.setProgress(percent);
        }

        @Override
        public void onMorseChanged(String morse, String text) {
            recvMorse.setText(MorseCodec.pretty(morse));
            recvText.setText(text);
        }

        @Override
        public void onError(String message) {
            recvStatus.setText(getString(R.string.mic_error, message == null ? "" : message));
        }

        @Override
        public void onStopped() {
            recvStart.setEnabled(true);
            recvStop.setEnabled(false);
            recvLevel.setProgress(0);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }
}
