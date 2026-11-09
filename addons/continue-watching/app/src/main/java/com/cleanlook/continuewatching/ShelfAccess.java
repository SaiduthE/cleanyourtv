package com.cleanlook.continuewatching;

/**
 * Who may read the shelf: this app itself, the Clean Your TV launcher by its package name, or any app signed with
 * the same key as this one (so a launcher built under another application id still works when both were signed
 * on the same machine). Everyone else gets a SecurityException. Pure, so the JVM tests cover it.
 */
final class ShelfAccess {
    private ShelfAccess() {}

    static final String LAUNCHER_PACKAGE = "com.gathin.tvhome";

    /**
     * @param sameApp          the caller runs under this app's own uid
     * @param sameSignature    the caller's signing certificate equals this app's
     * @param callingPackage   the package the system verified for the caller, or null
     */
    static boolean allowed(boolean sameApp, boolean sameSignature, String callingPackage) {
        return sameApp || sameSignature || LAUNCHER_PACKAGE.equals(callingPackage);
    }
}
