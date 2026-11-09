package io.github.maxhayim.brightness;

/**
 * Who may read or change the level through {@link QuickProvider}: this app itself, the Clean Your TV launcher by
 * its package name, or any app signed with the same key as this one (a launcher built under another application id
 * and signed on the same machine). Everyone else gets a SecurityException. Pure, so the JVM tests cover it.
 */
final class QuickAccess {
    static final String LAUNCHER_PACKAGE = "com.gathin.tvhome";

    private QuickAccess() {
    }

    static boolean allowed(boolean sameApp, boolean sameSignature, String callingPackage) {
        return sameApp || sameSignature || LAUNCHER_PACKAGE.equals(callingPackage);
    }
}
