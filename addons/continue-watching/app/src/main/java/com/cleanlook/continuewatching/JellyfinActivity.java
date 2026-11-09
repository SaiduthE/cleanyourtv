package com.cleanlook.continuewatching;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Add or change the Jellyfin server with the remote's keyboard: address, user, and a password or an API key.
 * Test signs in and says what it found without keeping anything; Save does the same and keeps the result. The
 * password is never stored, only the access token, encrypted by {@link SecureStore}.
 */
public final class JellyfinActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private EditText address;
    private EditText user;
    private EditText secret;
    private TextView mode;
    private TextView result;
    private boolean apiKey;
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_jellyfin);
        prefs = App.prefs(this);
        address = findViewById(R.id.address);
        user = findViewById(R.id.user);
        secret = findViewById(R.id.secret);
        mode = findViewById(R.id.mode);
        result = findViewById(R.id.result);
        if (prefs.jellyfinConfigured()) {
            address.setText(prefs.serverUrl());
            user.setText(prefs.userName());
        }
        mode.setOnClickListener(v -> {
            apiKey = !apiKey;
            showMode();
        });
        findViewById(R.id.test).setOnClickListener(v -> run(false));
        findViewById(R.id.save).setOnClickListener(v -> run(true));
        showMode();
        address.requestFocus();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void showMode() {
        mode.setText(apiKey ? R.string.mode_api_key : R.string.mode_password);
        secret.setHint(apiKey ? R.string.hint_api_key : R.string.hint_password);
        user.setHint(apiKey ? R.string.hint_user_api : R.string.hint_user);
        secret.setText("");
    }

    private void say(String message, boolean good, boolean bad) {
        result.setText(message);
        result.setTextColor(getColor(good ? R.color.good : bad ? R.color.bad : R.color.text_dim));
    }

    private void run(final boolean save) {
        if (busy) return;
        busy = true;
        say(getString(R.string.working), false, false);
        final String a = address.getText().toString().trim();
        final String u = user.getText().toString().trim();
        final String s = secret.getText().toString();
        final boolean key = apiKey;
        io.execute(new Runnable() {
            @Override public void run() {
                final JellyfinSignIn.Result r = JellyfinSignIn.run(a, u, s, key, prefs.deviceId());
                final boolean saved = save && r.ok
                        && prefs.saveJellyfin(r.base, r.serverName, r.userId, r.userName, r.token);
                main.post(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        if (isDestroyed()) return;
                        if (save && r.ok && !saved) {
                            say(getString(R.string.keystore_refused), false, true);
                        } else if (saved) {
                            App.refreshImageAuth(JellyfinActivity.this);
                            startRefresh();
                            finish();
                        } else {
                            say(r.message, r.ok, !r.ok);
                        }
                    }
                });
            }
        });
    }

    /** Takes effect now: the new server's titles are read in the background. */
    private void startRefresh() {
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                Refresher.refresh(app, false, true);
            }
        }, "cw-jellyfin-refresh").start();
    }
}
