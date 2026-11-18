/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.messenger.asr;

import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Transcribes a voice message or a round video on the device: makes sure the file is downloaded, picks the
 * language model, decodes the audio and runs the recognizer. Results are delivered on the UI thread.
 */
public final class EasygramAsrTranscriber {

    public static final int ERROR_NO_MODEL = 1, ERROR_DOWNLOAD = 2, ERROR_DECODE = 3, ERROR_TOO_LONG = 4, ERROR_ENGINE = 5,
            /** The message seems to be in a language whose model is not downloaded (see {@code lang}). */
            ERROR_NO_LANG_MODEL = 6;

    public interface Callback {
        /**
         * {@code text} is null on failure, then {@code error} is one of the ERROR_ constants;
         * {@code lang} is the language the message was taken for.
         */
        void onResult(@Nullable String text, int error, @Nullable String lang);
    }

    /** Whether the transcribe button should transcribe this message locally. */
    public static boolean canTranscribe(@Nullable MessageObject m) {
        if (m == null || m.messageOwner == null || !(m.isVoice() || m.isRoundVideo())) {
            return false;
        }
        if (!EasygramAsrSettings.isEnabled()) {
            return false;
        }
        return EasygramAsrModelManager.readyModelFor(EasygramAsrModels.LANG_RU) != null
                || EasygramAsrModelManager.readyModelFor(EasygramAsrModels.LANG_EN) != null;
    }

    /**
     * Whether Telegram's servers would really transcribe this message: an actual Premium subscription (not the
     * local "Premium access" switch of easygram, which the server doesn't know about) or a group that allows it.
     */
    public static boolean hasServerTranscription(MessageObject m) {
        return org.telegram.ui.Components.TranscribeButton.hasRealPremium(m.currentAccount)
                || org.telegram.ui.Components.TranscribeButton.isFreeTranscribeInChat(m);
    }

    /**
     * File → audio → language (by the voice when the identification model is downloaded, otherwise by the chat)
     * → model → text.
     *
     * @param allowOtherLang when the message seems to be in a language without a downloaded model, transcribe it
     *                       with the model of the other language anyway instead of failing with ERROR_NO_LANG_MODEL
     */
    public static void transcribe(MessageObject m, @Nullable String forcedLang, boolean allowOtherLang, Callback callback) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        resolveFile(m, file -> {
            log("file " + (file == null ? "null" : file.getAbsolutePath() + " " + file.length() + " bytes"));
            if (file == null) {
                callback.onResult(null, ERROR_DOWNLOAD, null);
                return;
            }
            final boolean byVoice = forcedLang == null && EasygramAsrSettings.getLangMode() == EasygramAsrSettings.LANG_AUTO;
            EasygramAsrEngine.queue.postRunnable(() -> {
                float[] samples;
                try {
                    samples = EasygramAsrAudio.decode(file, cancelled);
                } catch (EasygramAsrAudio.TooLongException e) {
                    AndroidUtilities.runOnUIThread(() -> callback.onResult(null, ERROR_TOO_LONG, null));
                    return;
                } catch (Throwable e) {
                    FileLog.e(e);
                    samples = null;
                }
                if (samples == null) {
                    AndroidUtilities.runOnUIThread(() -> callback.onResult(null, ERROR_DECODE, null));
                    return;
                }
                float peak = 0;
                for (float v : samples) peak = Math.max(peak, Math.abs(v));
                log("decoded " + samples.length + " samples (" + samples.length / (float) EasygramAsrAudio.SAMPLE_RATE + " s), peak " + peak);
                String voiceLang = byVoice ? EasygramAsrEngine.identifyLanguage(samples) : null;
                final float[] audio = samples;
                AndroidUtilities.runOnUIThread(() -> pickLanguage(m, forcedLang, voiceLang, (lang, confident) -> {
                    EasygramAsrModels.Model model = EasygramAsrModelManager.readyModelFor(lang);
                    if (model == null) {
                        String other = EasygramAsrModels.LANG_RU.equals(lang) ? EasygramAsrModels.LANG_EN : EasygramAsrModels.LANG_RU;
                        EasygramAsrModels.Model fallback = EasygramAsrModelManager.readyModelFor(other);
                        if (fallback == null) {
                            callback.onResult(null, ERROR_NO_MODEL, lang);
                            return;
                        }
                        if (confident && !allowOtherLang) {
                            // a Russian model turns English speech into Cyrillic nonsense (and back): ask first
                            callback.onResult(null, ERROR_NO_LANG_MODEL, lang);
                            return;
                        }
                        model = fallback;
                    }
                    final EasygramAsrModels.Model finalModel = model;
                    log("lang " + lang + " confident=" + confident + " model " + model.id);
                    EasygramAsrEngine.queue.postRunnable(() -> {
                        String text = null;
                        int error = 0;
                        try {
                            text = EasygramAsrEngine.transcribe(finalModel, audio, cancelled, null);
                            if (text == null) {
                                error = ERROR_ENGINE;
                            }
                        } catch (Throwable e) {
                            FileLog.e(e);
                            error = ERROR_ENGINE;
                        }
                        final String finalText = text;
                        final int finalError = error;
                        AndroidUtilities.runOnUIThread(() -> callback.onResult(finalText, finalError, finalModel.lang));
                    });
                }));
            });
        });
    }

    // file ----------------------------------------------------------------------------------------------------------

    private interface FileCallback {
        void onFile(@Nullable File file);
    }

    private static void resolveFile(MessageObject m, FileCallback callback) {
        int account = m.currentAccount;
        File file = null;
        if (!TextUtils.isEmpty(m.messageOwner.attachPath)) {
            File attach = new File(m.messageOwner.attachPath);
            if (attach.exists() && attach.length() > 0) {
                file = attach;
            }
        }
        if (file == null) {
            File path = FileLoader.getInstance(account).getPathToMessage(m.messageOwner);
            if (path != null && path.exists() && path.length() > 0) {
                file = path;
            }
        }
        if (file != null) {
            callback.onFile(file);
            return;
        }
        TLRPC.Document document = m.getDocument();
        if (document == null) {
            callback.onFile(null);
            return;
        }
        String fileName = FileLoader.getAttachFileName(document);
        NotificationCenter center = NotificationCenter.getInstance(account);
        NotificationCenter.NotificationCenterDelegate observer = new NotificationCenter.NotificationCenterDelegate() {
            @Override
            public void didReceivedNotification(int id, int acc, Object... args) {
                if (args.length == 0 || !fileName.equals(args[0])) {
                    return;
                }
                center.removeObserver(this, NotificationCenter.fileLoaded);
                center.removeObserver(this, NotificationCenter.fileLoadFailed);
                if (id == NotificationCenter.fileLoaded) {
                    File loaded = args.length > 1 && args[1] instanceof File ? (File) args[1] : FileLoader.getInstance(account).getPathToMessage(m.messageOwner);
                    callback.onFile(loaded != null && loaded.exists() ? loaded : null);
                } else {
                    callback.onFile(null);
                }
            }
        };
        center.addObserver(observer, NotificationCenter.fileLoaded);
        center.addObserver(observer, NotificationCenter.fileLoadFailed);
        log("downloading " + fileName);
        FileLoader.getInstance(account).loadFile(document, m, FileLoader.PRIORITY_HIGH, 0);
        // never leave the button spinning (and ignoring taps) if no load event ever comes
        AndroidUtilities.runOnUIThread(() -> {
            if (center.hasObservers(NotificationCenter.fileLoaded) && removeIfPending(center, observer)) {
                File path = FileLoader.getInstance(account).getPathToMessage(m.messageOwner);
                log("download wait timed out, file " + (path != null && path.exists() ? "exists" : "missing"));
                callback.onFile(path != null && path.exists() && path.length() > 0 ? path : null);
            }
        }, 90_000);
    }

    private static boolean removeIfPending(NotificationCenter center, NotificationCenter.NotificationCenterDelegate observer) {
        java.util.ArrayList<NotificationCenter.NotificationCenterDelegate> list = center.getObservers(NotificationCenter.fileLoaded);
        if (list == null || !list.contains(observer)) {
            return false;
        }
        center.removeObserver(observer, NotificationCenter.fileLoaded);
        center.removeObserver(observer, NotificationCenter.fileLoadFailed);
        return true;
    }

    /**
     * Letters of real words only: links, mentions, hashtags, e-mails, commands, file names and words with digits
     * are Latin in any language and would make every chat look English.
     */
    static int[] countScripts(String text) {
        int cyrillic = 0, latin = 0;
        int n = Math.min(text.length(), 1000);
        int i = 0;
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) i++;
            int start = i;
            while (i < n && !Character.isWhitespace(text.charAt(i))) i++;
            if (start == i) continue;
            String token = text.substring(start, i);
            if (token.contains("://") || token.startsWith("www.") || token.indexOf('@') >= 0 || token.indexOf('#') >= 0
                    || token.startsWith("/") || token.indexOf('_') >= 0 || token.matches(".*\\w\\.\\w.*") || token.matches(".*\\d.*")) {
                continue;
            }
            for (int k = 0; k < token.length(); k++) {
                char c = token.charAt(k);
                if (c >= '\u0400' && c <= '\u04FF') {
                    cyrillic++;
                } else if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                    latin++;
                }
            }
        }
        return new int[]{cyrillic, latin};
    }

    static void log(String message) {
        if (org.telegram.messenger.BuildVars.LOGS_ENABLED) {
            FileLog.d("easygram asr: " + message);
        }
    }

    // language ------------------------------------------------------------------------------------------------------

    private interface LangCallback {
        /** {@code confident}: chosen by the user, or the chat's text clearly is in that language. */
        void onLang(String lang, boolean confident);
    }

    private static final class CachedLang {
        final String lang;
        final boolean confident;
        final long time;

        CachedLang(String lang, boolean confident) {
            this.lang = lang;
            this.confident = confident;
            this.time = SystemClock.elapsedRealtime();
        }
    }

    private static final HashMap<Long, CachedLang> dialogLangs = new HashMap<>();

    /**
     * Forced language, then the setting; in "auto" mode the language heard by the identification model, else the
     * script of the recent text messages of the chat (Cyrillic or Latin), and the app language when there is too
     * little text (then not confident).
     */
    private static void pickLanguage(MessageObject m, @Nullable String forcedLang, @Nullable String voiceLang, LangCallback callback) {
        if (forcedLang != null) {
            callback.onLang(forcedLang, true);
            return;
        }
        if (EasygramAsrModels.LANG_RU.equals(voiceLang) || EasygramAsrModels.LANG_EN.equals(voiceLang)) {
            callback.onLang(voiceLang, true); // heard in the message itself: better than any guess from the chat
            return;
        }
        if (voiceLang != null) {
            // Ukrainian, Belarusian... are closer to the Russian model; anything else: fall back to the chat
            boolean slavic = "uk".equals(voiceLang) || "be".equals(voiceLang) || "bg".equals(voiceLang) || "sr".equals(voiceLang) || "kk".equals(voiceLang);
            if (slavic) {
                callback.onLang(EasygramAsrModels.LANG_RU, false);
                return;
            }
        }
        int mode = EasygramAsrSettings.getLangMode();
        if (mode == EasygramAsrSettings.LANG_RU) {
            callback.onLang(EasygramAsrModels.LANG_RU, true);
            return;
        }
        if (mode == EasygramAsrSettings.LANG_EN) {
            callback.onLang(EasygramAsrModels.LANG_EN, true);
            return;
        }
        long dialogId = m.getDialogId();
        CachedLang cached = dialogLangs.get(dialogId);
        if (cached != null && SystemClock.elapsedRealtime() - cached.time < 10 * 60_000) {
            callback.onLang(cached.lang, cached.confident);
            return;
        }
        int account = m.currentAccount;
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            int cyrillic = 0, latin = 0;
            SQLiteCursor cursor = null;
            try {
                cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                        "SELECT data FROM messages_v2 WHERE uid = %d ORDER BY mid DESC LIMIT 60", dialogId));
                while (cursor.next()) {
                    NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data == null) {
                        continue;
                    }
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    data.reuse();
                    if (message == null || TextUtils.isEmpty(message.message)) {
                        continue;
                    }
                    int[] counts = countScripts(message.message);
                    cyrillic += counts[0];
                    latin += counts[1];
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            String ui = LocaleController.getInstance().getCurrentLocale().getLanguage();
            String uiLang = "ru".equals(ui) || "uk".equals(ui) || "be".equals(ui) || "kk".equals(ui)
                    ? EasygramAsrModels.LANG_RU : EasygramAsrModels.LANG_EN;
            String lang;
            boolean confident;
            if (cyrillic + latin < 40) {
                lang = uiLang;
                confident = false;
            } else {
                lang = cyrillic >= latin ? EasygramAsrModels.LANG_RU : EasygramAsrModels.LANG_EN;
                // going against the app language needs a clearer majority
                int ratio = lang.equals(uiLang) ? 2 : 4;
                confident = Math.max(cyrillic, latin) >= ratio * Math.min(cyrillic, latin);
            }
            if (dialogId == org.telegram.messenger.UserConfig.getInstance(account).getClientUserId()) {
                confident = false; // Saved Messages: notes, links and forwards say little about a voice message
            }
            log("chat script cyrillic=" + cyrillic + " latin=" + latin + " -> " + lang + (confident ? " (confident)" : ""));
            final String finalLang = lang;
            final boolean finalConfident = confident;
            AndroidUtilities.runOnUIThread(() -> {
                dialogLangs.put(dialogId, new CachedLang(finalLang, finalConfident));
                callback.onLang(finalLang, finalConfident);
            });
        });
    }

    private EasygramAsrTranscriber() {
    }
}
