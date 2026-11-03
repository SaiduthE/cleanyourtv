package com.cleanlook.continuewatching;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Settings. The Jellyfin token (or API key) is stored encrypted (SecureStore); nothing else here is secret. */
final class Prefs {
    static final String JF_NONE = "none";
    static final String JF_OK = "ok";
    static final String JF_SIGN_IN = "signin";
    static final String JF_UNREACHABLE = "unreachable";

    private static final String FILE = "cw";
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---- Jellyfin
    String serverUrl() { return sp.getString("jf_url", ""); }
    String serverName() { return sp.getString("jf_name", ""); }
    String userId() { return sp.getString("jf_user", ""); }
    String userName() { return sp.getString("jf_user_name", ""); }
    String token() { return SecureStore.decrypt(sp.getString("jf_token", "")); }

    boolean jellyfinConfigured() {
        return !serverUrl().isEmpty() && !userId().isEmpty() && token() != null;
    }

    /** Returns false when the Keystore would not encrypt the token (nothing is saved then). */
    boolean saveJellyfin(String url, String serverName, String userId, String userName, String token) {
        String blob = SecureStore.encrypt(token);
        if (blob == null) return false;
        sp.edit().putString("jf_url", url).putString("jf_name", serverName).putString("jf_user", userId)
                .putString("jf_user_name", userName).putString("jf_token", blob)
                .putString("jf_status", JF_OK).apply();
        return true;
    }

    void clearJellyfin() {
        sp.edit().remove("jf_url").remove("jf_name").remove("jf_user").remove("jf_user_name").remove("jf_token")
                .putString("jf_status", JF_NONE).apply();
    }

    String jellyfinStatus() { return sp.getString("jf_status", JF_NONE); }
    void setJellyfinStatus(String s) { sp.edit().putString("jf_status", s).apply(); }

    /** Stable per install, sent as DeviceId. */
    String deviceId() {
        String d = sp.getString("device_id", null);
        if (d == null) {
            d = UUID.randomUUID().toString();
            sp.edit().putString("device_id", d).apply();
        }
        return d;
    }

    boolean showResume() { return sp.getBoolean("jf_resume", true); }
    boolean showNextUp() { return sp.getBoolean("jf_next", true); }
    void setShowResume(boolean v) { sp.edit().putBoolean("jf_resume", v).apply(); }
    void setShowNextUp(boolean v) { sp.edit().putBoolean("jf_next", v).apply(); }

    // ---- Watch Next apps the owner switched off
    Set<String> disabledPackages() { return new HashSet<>(sp.getStringSet("disabled_pkgs", new HashSet<String>())); }

    void setPackageEnabled(String pkg, boolean enabled) {
        Set<String> s = disabledPackages();
        if (enabled) s.remove(pkg);
        else s.add(pkg);
        sp.edit().putStringSet("disabled_pkgs", s).apply();
    }

    // ---- shelf provider for the launcher
    boolean shelfEnabled() { return sp.getBoolean("shelf_enabled", true); }
    void setShelfEnabled(boolean v) { sp.edit().putBoolean("shelf_enabled", v).apply(); }

    // ---- what the first-run probe found: package -> [Watch Next rows, preview rows], see Probe.encode
    long probeAt() { return sp.getLong("probe_at", 0); }
    String probeJson() { return sp.getString("probe", ""); }

    void saveProbe(String json, long now) {
        sp.edit().putString("probe", json).putLong("probe_at", now).apply();
    }
}
