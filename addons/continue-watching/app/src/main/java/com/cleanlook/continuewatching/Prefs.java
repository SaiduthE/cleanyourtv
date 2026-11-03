package com.cleanlook.continuewatching;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
        addSource(AddedSources.JELLYFIN);
        return true;
    }

    void clearJellyfin() {
        sp.edit().remove("jf_url").remove("jf_name").remove("jf_user").remove("jf_user_name").remove("jf_token")
                .putString("jf_status", JF_NONE).apply();
        removeSource(AddedSources.JELLYFIN);
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

    // ---- preview channels: only the owner's explicit choices are stored, as "package|channel name"; the rest follows
    // ChannelFilter.isContinueName
    Set<String> channelsForcedOn() { return new HashSet<>(sp.getStringSet("chan_on", new HashSet<String>())); }
    Set<String> channelsForcedOff() { return new HashSet<>(sp.getStringSet("chan_off", new HashSet<String>())); }

    /** By name only (no item data): used for the migration. The screens use the overload with {@code startedKeys}. */
    boolean channelEnabled(String pkg, String name) {
        return ChannelFilter.enabled(pkg, name, channelsForcedOn(), channelsForcedOff());
    }

    /** The effective state, the one the hub and Top Shelf apply: see {@link ChannelFilter#startedKeys}. */
    boolean channelEnabled(String pkg, String name, Set<String> startedKeys) {
        return ChannelFilter.enabled(pkg, name, channelsForcedOn(), channelsForcedOff(), startedKeys);
    }

    void setChannelEnabled(String pkg, String name, boolean enabled) {
        String k = ChannelFilter.key(pkg, name);
        Set<String> on = channelsForcedOn();
        Set<String> off = channelsForcedOff();
        on.remove(k);
        off.remove(k);
        (enabled ? on : off).add(k);
        sp.edit().putStringSet("chan_on", on).putStringSet("chan_off", off).apply();
    }

    // ---- the sources the owner added: keys in the order they were added (an app's package, or AddedSources.JELLYFIN)
    List<String> addedSources() { return AddedSources.parse(sp.getString("added_sources", "")); }

    boolean isAdded(String key) { return addedSources().contains(key); }

    /** Adds a source with its defaults: switched on, its channels as their names say. */
    void addSource(String key) {
        List<String> now = addedSources();
        if (now.contains(key)) return;
        SharedPreferences.Editor e = sp.edit().putString("added_sources", AddedSources.join(AddedSources.with(now, key)));
        if (!AddedSources.JELLYFIN.equals(key)) {
            Set<String> dis = disabledPackages();
            dis.remove(key);
            e.putStringSet("disabled_pkgs", dis);
            e.putStringSet("chan_on", AddedSources.withoutPackage(channelsForcedOn(), key));
            e.putStringSet("chan_off", AddedSources.withoutPackage(channelsForcedOff(), key));
        }
        e.apply();
    }

    void removeSource(String key) {
        sp.edit().putString("added_sources", AddedSources.join(AddedSources.without(addedSources(), key))).apply();
    }

    boolean addedMigrated() { return sp.getBoolean("added_migrated", false); }

    /**
     * Once, for installs from before the added list: whatever was shown stays added. Does nothing for a new install
     * (nothing was shown), and never runs twice.
     */
    void migrateAdded(Store.State s) {
        if (addedMigrated()) return;
        List<String> pkgs = new ArrayList<>();
        for (Item it : s.wn) pkgs.add(it.pkg);
        List<String> keys = AddedSources.migrate(jellyfinConfigured(), Probe.decode(probeJson()), pkgs, s.chans,
                disabledPackages(), channelsForcedOn(), channelsForcedOff(), "");
        List<String> all = addedSources();
        for (String k : keys) all = AddedSources.with(all, k);
        sp.edit().putString("added_sources", AddedSources.join(all)).putBoolean("added_migrated", true).apply();
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
