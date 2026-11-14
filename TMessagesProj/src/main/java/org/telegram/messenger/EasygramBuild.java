package org.telegram.messenger;

/**
 * Which distribution channel this build is for. Set by the app module (ApplicationLoaderImpl) from its flavor:
 * the App Bundle (bundleAfat, BuildConfig.BUNDLE = true) always goes to Google Play, the APKs (afat) are published
 * on GitHub. Play builds must not update themselves and must not show the Telegram logo switch.
 */
public final class EasygramBuild {

    private EasygramBuild() {
    }

    static volatile boolean playStore;

    /** true: Google Play build (AAB) - no self-updater, always the "easygram" title. */
    public static boolean isPlayStore() {
        return playStore;
    }
}
