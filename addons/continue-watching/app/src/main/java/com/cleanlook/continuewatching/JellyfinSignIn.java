package com.cleanlook.continuewatching;

import org.json.JSONException;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Signs in to a Jellyfin server (password or API key) and says in plain words what happened. Blocking: call from a
 * worker thread. The password only travels to the server in the sign-in request; what is kept is the access token.
 * No address, name or secret goes to logcat.
 */
final class JellyfinSignIn {
    private JellyfinSignIn() {}

    static final class Result {
        boolean ok;
        /** What to show the owner. */
        String message = "";
        String base = "";
        String serverName = "";
        String userId = "";
        String userName = "";
        /** The access token, or the API key itself. Never shown. */
        String token = "";
    }

    static Result run(String address, String user, String secret, boolean apiKey, String deviceId) {
        Result r = new Result();
        String base = JellyfinParser.normalizeBase(address);
        if (base == null) return fail(r, "That does not look like a server address. Try 192.168.1.20:8096.");
        if (secret.isEmpty()) return fail(r, apiKey ? "Enter the API key." : "Enter the password.");
        if (user.isEmpty() && !apiKey) return fail(r, "Enter the user name.");
        r.base = base;
        try {
            r.serverName = JellyfinClient.serverName(base, deviceId);
        } catch (IOException e) {
            return fail(r, "Could not reach the server. Check the address, and that this TV and the server are on the same network.");
        }
        try {
            if (apiKey) {
                List<String[]> users = JellyfinClient.users(base, secret, deviceId);
                String[] pick = pickUser(users, user);
                if (pick == null) {
                    return fail(r, users.size() > 1 && user.isEmpty()
                            ? "The key works. Enter which user's list to show: " + names(users) + "."
                            : "The key works, but there is no user called \"" + user + "\". Users: " + names(users) + ".");
                }
                r.userId = pick[0];
                r.userName = pick[1];
                r.token = secret;
            } else {
                JellyfinParser.Auth a = JellyfinClient.byName(base, user, secret, deviceId);
                r.userId = a.userId;
                r.userName = a.userName.isEmpty() ? user : a.userName;
                r.token = a.token;
            }
            int n = new JellyfinClient(base, r.token, r.userId, deviceId).resume(System.currentTimeMillis()).size();
            r.ok = true;
            r.message = "Connected to " + r.serverName + " as " + r.userName + ". "
                    + (n == 0 ? "Nothing in progress right now." : n + (n == 1 ? " title" : " titles") + " in progress.");
            return r;
        } catch (JellyfinClient.AuthException e) {
            return fail(r, apiKey ? "The server refused that API key." : "Wrong user name or password.");
        } catch (IOException e) {
            return fail(r, "The server answered, but not as expected. Try again.");
        } catch (JSONException e) {
            return fail(r, "That is not a Jellyfin server: the answer was not understood.");
        }
    }

    /** The saved sign-in, tried again: how many titles are in progress, or why not. */
    static Result test(Prefs prefs) {
        Result r = new Result();
        try {
            JellyfinClient jc = new JellyfinClient(prefs.serverUrl(), prefs.token(), prefs.userId(), prefs.deviceId());
            int n = jc.resume(System.currentTimeMillis()).size();
            prefs.setJellyfinStatus(Prefs.JF_OK);
            r.ok = true;
            r.message = "Connected to " + prefs.serverName() + ". "
                    + (n == 0 ? "Nothing in progress right now." : n + (n == 1 ? " title" : " titles") + " in progress.");
        } catch (JellyfinClient.AuthException e) {
            prefs.setJellyfinStatus(Prefs.JF_SIGN_IN);
            r.message = "The server no longer accepts this sign-in. Edit the server and sign in again.";
        } catch (IOException e) {
            prefs.setJellyfinStatus(Prefs.JF_UNREACHABLE);
            r.message = "Could not reach " + prefs.serverName() + ". Is the server on?";
        } catch (JSONException e) {
            r.message = "The server's answer was not understood.";
        }
        return r;
    }

    /** The user to show: the one named (any case), or the only one when none is named. */
    static String[] pickUser(List<String[]> users, String name) {
        if (name.isEmpty()) return users.size() == 1 ? users.get(0) : null;
        for (String[] u : users) if (u[1].toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) return u;
        return null;
    }

    private static String names(List<String[]> users) {
        StringBuilder b = new StringBuilder();
        for (String[] u : users) b.append(b.length() == 0 ? "" : ", ").append(u[1]);
        return b.toString();
    }

    private static Result fail(Result r, String message) {
        r.ok = false;
        r.message = message;
        return r;
    }
}
