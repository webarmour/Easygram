/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.messenger;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.widget.RemoteViews;

import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.M3ExpressiveTrack;
import org.telegram.ui.Components.M3PlayPauseIcon;
import org.telegram.ui.LaunchActivity;

/**
 * Drives the home-screen music widget ({@link MusicPlayerWidgetProvider}).
 *
 * Launchers render widgets through RemoteViews, so nothing animates by itself there. While music plays (and
 * the screen is on) this pushes pre-rendered frames ~25x per second. To keep that cheap the frames are
 * single-channel ALPHA_8 masks tinted by the ImageViews (4x less data than colour bitmaps): the wave in two
 * layers (played part + thumb, rest of the track) and the play button in two layers (container, icon); the
 * button is only re-sent when it actually changed. Cover, texts, tints and click intents are sent only when
 * the track / state / size changes. Paused or screen off: 1 Hz state check; no widget placed: stops.
 */
public class MusicWidgetUpdater {

    private static volatile MusicWidgetUpdater instance;

    public static MusicWidgetUpdater getInstance() {
        if (instance == null) {
            synchronized (MusicWidgetUpdater.class) {
                if (instance == null) instance = new MusicWidgetUpdater();
            }
        }
        return instance;
    }

    public static final String ACTION_SEEK = "io.github.webarmour.easygram.widget.seek";
    public static final String EXTRA_PROGRESS = "progress";
    private static final int[] SEEK_ZONE_IDS = {R.id.widget_music_seek_0, R.id.widget_music_seek_1, R.id.widget_music_seek_2, R.id.widget_music_seek_3, R.id.widget_music_seek_4, R.id.widget_music_seek_5, R.id.widget_music_seek_6, R.id.widget_music_seek_7, R.id.widget_music_seek_8, R.id.widget_music_seek_9, R.id.widget_music_seek_10, R.id.widget_music_seek_11, R.id.widget_music_seek_12, R.id.widget_music_seek_13, R.id.widget_music_seek_14, R.id.widget_music_seek_15, R.id.widget_music_seek_16, R.id.widget_music_seek_17, R.id.widget_music_seek_18, R.id.widget_music_seek_19, R.id.widget_music_seek_20, R.id.widget_music_seek_21, R.id.widget_music_seek_22, R.id.widget_music_seek_23};
    private static final int SEEK_ZONES = SEEK_ZONE_IDS.length; // tap zones over the wave (RemoteViews has no tap coordinates)

    private static final long FRAME_MS = 40;   // ~25 fps while playing
    private static final long IDLE_MS = 1000;
    private static final float MASK_SCALE = 1.4f; // px per dp of the frames
    private static final int COMPACT_MAX_HEIGHT_DP = 200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final M3ExpressiveTrack track = new M3ExpressiveTrack();
    private final M3PlayPauseIcon playIcon = new M3PlayPauseIcon();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean running;
    private boolean forceFull = true;

    private long lastMessageKey = Long.MIN_VALUE;
    private boolean lastPlaying, lastHadLevels, lastNight;
    private float lastButtonState = -1;
    private float lastProgress = -1;
    private float displayedProgress, progressAnimFrom, progressAnimTo;
    private long progressAnimStart;
    private long animKey = Long.MIN_VALUE;

    private Bitmap coverBitmap;
    private int coverSeed; // dominant/vibrant colour of the cover, 0 = none
    private ImageReceiver coverReceiver;
    private long coverForKey = Long.MIN_VALUE;

    private MusicWidgetUpdater() {
        track.setReactToBeat(true);
    }

    /** Starts (or keeps) the update loop and forces a full refresh (state / size may have changed). */
    public void start() {
        AndroidUtilities.runOnUIThread(() -> {
            forceFull = true;
            if (!running) {
                running = true;
                handler.post(tick);
            }
        });
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long next;
            try {
                next = update();
            } catch (Throwable e) {
                FileLog.e(e);
                next = IDLE_MS;
            }
            if (next < 0) {
                running = false;
                return;
            }
            handler.postDelayed(this, next);
        }
    };

    private long update() {
        Context context = ApplicationLoader.applicationContext;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] small = manager.getAppWidgetIds(new ComponentName(context, MusicPlayerWidgetProvider.class));
        int[] large = manager.getAppWidgetIds(new ComponentName(context, MusicPlayerWidgetLargeProvider.class));
        int[] ids = new int[(small != null ? small.length : 0) + (large != null ? large.length : 0)];
        if (small != null) System.arraycopy(small, 0, ids, 0, small.length);
        if (large != null) System.arraycopy(large, 0, ids, ids.length - large.length, large.length);
        if (ids.length == 0) {
            return -1;
        }
        MediaController mc = MediaController.getInstance();
        MessageObject message = mc.getPlayingMessageObject();
        // songs only: voice messages / round videos never show up in the widget (it stays in the empty state)
        if (message != null && !message.isMusic()) {
            message = null;
        }
        boolean hasMessage = message != null;
        boolean playing = hasMessage && !mc.isMessagePaused();
        boolean night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        float[] levels = null;
        if (hasMessage && message.isMusic()) {
            levels = AudioWaveformGenerator.get(message);
            if (levels == null) AudioWaveformGenerator.request(message, null);
        }

        long key = hasMessage ? (((long) message.currentAccount) << 56) ^ (message.getDialogId() * 1000003L) ^ message.getId() : 0;
        if (key != coverForKey) {
            coverForKey = key;
            loadCover(hasMessage ? message : null);
        }
        boolean full = forceFull || key != lastMessageKey || playing != lastPlaying || (levels != null) != lastHadLevels || night != lastNight;
        if (key != lastMessageKey) {
            playIcon.setPause(playing, false);
        }
        forceFull = false;
        lastMessageKey = key;
        lastPlaying = playing;
        lastHadLevels = levels != null;
        lastNight = night;

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean interactive = pm == null || pm.isInteractive();
        float targetProgress = hasMessage ? message.audioProgress : 0f;
        boolean progressChanged = Math.abs(targetProgress - lastProgress) > 0.0005f;
        // seeks / track jumps glide the thumb over ~350 ms (ease-out) instead of teleporting it
        if (key != animKey || lastProgress < 0) {
            animKey = key;
            progressAnimFrom = progressAnimTo = targetProgress;
            progressAnimStart = 0;
        } else if (Math.abs(targetProgress - progressAnimTo) > 0.015f) {
            progressAnimFrom = displayedProgress;
            progressAnimStart = android.os.SystemClock.elapsedRealtime();
        }
        progressAnimTo = targetProgress;
        lastProgress = targetProgress;
        float progress = targetProgress;
        boolean progressAnimating = false;
        if (progressAnimStart != 0) {
            float t = (android.os.SystemClock.elapsedRealtime() - progressAnimStart) / 350f;
            if (t >= 1f) {
                progressAnimStart = 0;
            } else {
                float e = 1f - (1f - t) * (1f - t) * (1f - t);
                // keep following the (moving) target while gliding
                progress = progressAnimFrom + (targetProgress - progressAnimFrom) * e;
                progressAnimating = true;
            }
        }
        displayedProgress = progress;

        // keep sending frames while the play<->pause morph (or the wave settling) is still animating, otherwise
        // only its first frame would reach the launcher and the icon would freeze
        playIcon.setPause(playing, true);
        boolean iconAnimating = playIcon.update();
        boolean settling = iconAnimating || progressAnimating || track.getBeatPulse() > 0.01f
                || coverPulse > 0.01f || coverBass > 0.01f || coverEnergy > 0.01f; // cover easing back to rest
        if (!full && !progressChanged && (!interactive || (!playing && !settling))) {
            return IDLE_MS;
        }
        track.update(playing, levels);
        float pulse = 0f, bassTremble = 0f, energy = 0f;
        if (playing) {
            MusicBeatTracker tracker = MusicBeatTracker.getInstance();
            tracker.markInterest();
            pulse = tracker.getPulse();
            bassTremble = tracker.getBassTremble();
            energy = tracker.getEnergy();
        }
        // smoothed so the cover doesn't flicker between frames, and settles back to rest when paused
        coverPulse += (pulse - coverPulse) * (pulse > coverPulse ? 0.7f : 0.35f);
        coverBass += (bassTremble - coverBass) * 0.35f;
        coverEnergy += (energy - coverEnergy) * 0.2f;
        settling = settling || coverPulse > 0.01f || coverBass > 0.01f || coverEnergy > 0.01f;
        // re-send the button only when it visibly changed (beat bump or morph)
        float buttonState = playIcon.getMorph() * 10f + Math.round(pulse * 40f) / 40f;
        boolean sendButton = full || iconAnimating || Math.abs(buttonState - lastButtonState) > 0.001f;
        lastButtonState = buttonState;

        Colors colors = new Colors(context, night, coverSeed);
        for (int id : ids) {
            Bundle options = manager.getAppWidgetOptions(id);
            int widthDp = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) : 0;
            int heightDp = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) : 0;
            if (widthDp <= 0) widthDp = 300;
            boolean compact = heightDp <= 0 || heightDp < COMPACT_MAX_HEIGHT_DP;
            int layout = compact ? R.layout.widget_music_player_compact : R.layout.widget_music_player;
            int waveWidthDp = Math.max(120, Math.min(600, widthDp - (compact ? 24 : 32)));
            int waveHeightDp = compact ? 28 : 32;
            int playDp = compact ? 48 : 64;

            RemoteViews views = new RemoteViews(context.getPackageName(), layout);
            Bitmap[] wave = renderWave(waveWidthDp, waveHeightDp, progress, levels);
            views.setImageViewBitmap(R.id.widget_music_wave, wave[0]);
            views.setImageViewBitmap(R.id.widget_music_wave_rest, wave[1]);
            applyCoverMotion(views);
            if (sendButton) {
                Bitmap[] play = renderPlay(playDp, pulse);
                views.setImageViewBitmap(R.id.widget_music_play_bg, play[0]);
                views.setImageViewBitmap(R.id.widget_music_play_icon, play[1]);
            }
            if (full) {
                fillStatic(context, views, message, hasMessage, playing, colors, compact, heightDp);
                manager.updateAppWidget(id, views);
            } else {
                manager.partiallyUpdateAppWidget(id, views);
            }
        }
        return interactive && (playing || iconAnimating || settling) ? FRAME_MS : IDLE_MS;
    }

    private void fillStatic(Context context, RemoteViews views, MessageObject message, boolean hasMessage, boolean playing,
                            Colors colors, boolean compact, int heightDp) {
        String title, author;
        if (hasMessage) {
            title = message.getMusicTitle();
            author = message.getMusicAuthor();
        } else {
            title = LocaleController.getString(R.string.EasygramWidgetNothingPlaying);
            author = null; // empty state: only "Nothing is playing", no second line
        }
        views.setTextViewText(R.id.widget_music_title, TextUtils.isEmpty(title) ? "—" : title);
        views.setTextViewText(R.id.widget_music_author, author == null ? "" : author);
        views.setViewVisibility(R.id.widget_music_author, hasMessage ? android.view.View.VISIBLE : android.view.View.GONE);
        // empty state: no progress bar (and no seek zones) either
        views.setViewVisibility(R.id.widget_music_wave_container, hasMessage ? android.view.View.VISIBLE : android.view.View.GONE);
        views.setTextColor(R.id.widget_music_title, colors.text);
        views.setTextColor(R.id.widget_music_author, colors.textSecondary);
        int coverPx = compact ? 160 : Math.max(240, Math.min(600, (int) (Math.max(120, heightDp - 190) * 2.2f)));
        views.setImageViewBitmap(R.id.widget_music_cover, renderCover(context, colors, coverPx, hasMessage, lastNight));
        vinylShown = coverBitmap == null || coverBitmap.isRecycled();
        // tints of the alpha masks
        views.setInt(R.id.widget_music_wave, "setColorFilter", colors.accent);
        views.setInt(R.id.widget_music_wave_rest, "setColorFilter", colors.track);
        views.setInt(R.id.widget_music_play_bg, "setColorFilter", colors.accent);
        views.setInt(R.id.widget_music_play_icon, "setColorFilter", colors.onAccent);
        views.setInt(R.id.widget_music_prev, "setColorFilter", colors.text);
        views.setInt(R.id.widget_music_next, "setColorFilter", colors.text);
        if (Build.VERSION.SDK_INT >= 31) {
            // widget surface and button chips take the cover's colours
            views.setColorStateList(R.id.widget_music_root, "setBackgroundTintList", android.content.res.ColorStateList.valueOf(colors.surface));
            views.setColorStateList(R.id.widget_music_prev, "setBackgroundTintList", android.content.res.ColorStateList.valueOf(colors.container));
            views.setColorStateList(R.id.widget_music_next, "setBackgroundTintList", android.content.res.ColorStateList.valueOf(colors.container));
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        ComponentName receiver = new ComponentName(context, MusicPlayerReceiver.class);
        Intent open = new Intent(context, LaunchActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openApp = PendingIntent.getActivity(context, 104, open, flags);
        // without a song (nothing / a voice message playing) the controls must not drive the voice playlist
        views.setOnClickPendingIntent(R.id.widget_music_prev, hasMessage ? PendingIntent.getBroadcast(context, 101,
                new Intent(MusicPlayerService.NOTIFY_PREVIOUS).setComponent(receiver), flags) : openApp);
        views.setOnClickPendingIntent(R.id.widget_music_next, hasMessage ? PendingIntent.getBroadcast(context, 102,
                new Intent(MusicPlayerService.NOTIFY_NEXT).setComponent(receiver), flags) : openApp);
        views.setOnClickPendingIntent(R.id.widget_music_play, hasMessage
                ? PendingIntent.getBroadcast(context, 103, new Intent(playing ? MusicPlayerService.NOTIFY_PAUSE : MusicPlayerService.NOTIFY_PLAY).setComponent(receiver), flags)
                : openApp);
        views.setOnClickPendingIntent(R.id.widget_music_cover, openApp);
        views.setOnClickPendingIntent(R.id.widget_music_texts, openApp);

        // seek: static invisible zones over the wave (widget_music_seek_zones.xml), each seeks to its centre
        for (int i = 0; i < SEEK_ZONES; i++) {
            Intent seek = new Intent(ACTION_SEEK).setComponent(receiver).putExtra(EXTRA_PROGRESS, (i + 0.5f) / SEEK_ZONES);
            views.setOnClickPendingIntent(SEEK_ZONE_IDS[i], hasMessage ? PendingIntent.getBroadcast(context, 200 + i, seek, flags) : openApp);
        }
    }

    // ---------------------------------------------------------------- cover motion

    private float coverPulse, coverBass, coverEnergy;
    private boolean vinylShown;
    private float vinylAngle;
    private long vinylLastFrame;
    private final long motionStart = android.os.SystemClock.elapsedRealtime();

    /**
     * The cover reacts to the audio without re-sending its bitmap: View scale / translation / rotation are
     * remotable on Android 12+, so each frame only carries a few floats.
     *  - kick / snare / clap: a quick swell (up to ~7 %)
     *  - heavy bass: a fine shake (1-2 dp) with a slight tilt
     *  - loudness: gentle breathing
     */
    private void applyCoverMotion(RemoteViews views) {
        if (Build.VERSION.SDK_INT < 31) {
            return;
        }
        float t = (android.os.SystemClock.elapsedRealtime() - motionStart) / 1000f;
        float scale = 1f + 0.07f * coverPulse + 0.025f * coverEnergy;
        // incommensurate frequencies so the shake doesn't read as a regular wobble
        float shakePx = AndroidUtilities.dp(1.6f) * coverBass;
        float tx = shakePx * (float) (Math.sin(t * 2 * Math.PI * 13.1) * 0.7 + Math.sin(t * 2 * Math.PI * 7.3) * 0.3);
        float ty = shakePx * (float) (Math.cos(t * 2 * Math.PI * 11.7) * 0.7 + Math.sin(t * 2 * Math.PI * 5.9) * 0.3);
        float rot = 1.2f * coverBass * (float) Math.sin(t * 2 * Math.PI * 9.4);
        // the vinyl placeholder spins at 33 1/3 rpm while music plays
        long now = android.os.SystemClock.elapsedRealtime();
        if (vinylShown && lastPlaying) {
            long dt = vinylLastFrame == 0 ? 0 : Math.min(200, now - vinylLastFrame);
            vinylAngle = (vinylAngle + dt * 0.2f) % 360f;
        }
        vinylLastFrame = now;
        if (vinylShown) rot += vinylAngle;
        views.setFloat(R.id.widget_music_cover, "setScaleX", scale);
        views.setFloat(R.id.widget_music_cover, "setScaleY", scale);
        views.setFloat(R.id.widget_music_cover, "setTranslationX", tx);
        views.setFloat(R.id.widget_music_cover, "setTranslationY", ty);
        views.setFloat(R.id.widget_music_cover, "setRotation", rot);
    }

    // ---------------------------------------------------------------- rendering (alpha masks)

    /** @return {played part + thumb, rest of the track} */
    private Bitmap[] renderWave(int widthDp, int heightDp, float progress, float[] levels) {
        int w = Math.round(widthDp * MASK_SCALE), h = Math.round(heightDp * MASK_SCALE);
        Bitmap played = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Bitmap rest = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        float s = MASK_SCALE / AndroidUtilities.density; // draw in device px (AndroidUtilities.dp) units
        float W = w / s, H = h / s;
        float left = AndroidUtilities.dp(4), right = W - AndroidUtilities.dp(4), cy = H / 2f;
        float x = left + (right - left) * Math.max(0, Math.min(1, progress));
        float gap = AndroidUtilities.dp(5);

        Canvas cp = new Canvas(played);
        cp.scale(s, s);
        Canvas cr = new Canvas(rest);
        cr.scale(s, s);
        if (levels != null) {
            float stroke = AndroidUtilities.dp(2.2f), amp = Math.min(AndroidUtilities.dp(10), H / 2f / 1.35f), wl = AndroidUtilities.dp(8);
            track.drawLoudness(cp, left, x - gap, left, right, cy, stroke, amp, wl, levels, Color.WHITE);
            track.drawLoudness(cr, x + gap, right, left, right, cy, stroke, amp, wl, levels, Color.WHITE);
        } else {
            track.drawLine(cr, x + gap, right, cy, AndroidUtilities.dp(3), Color.WHITE);
            if (x - gap > left) {
                track.drawWavy(cp, left, x - gap, cy, AndroidUtilities.dp(3), AndroidUtilities.dp(3), AndroidUtilities.dp(22), Color.WHITE);
            }
        }
        paint.setColor(Color.WHITE);
        float halfH = Math.min(H / 2f, AndroidUtilities.dp(11) * (1f + 0.45f * track.getBeatPulse()));
        M3ExpressiveTrack.drawThumb(cp, x, cy, AndroidUtilities.dp(2), halfH, paint);
        return new Bitmap[]{played, rest};
    }

    /** @return {container, icon} */
    private Bitmap[] renderPlay(int sizeDp, float pulse) {
        int size = Math.round(sizeDp * MASK_SCALE);
        Bitmap bg = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8);
        Bitmap icon = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8);
        float s = MASK_SCALE / AndroidUtilities.density;
        float S = size / s, c = S / 2f;
        float scale = 1f + 0.08f * pulse;
        float radius = S / 2f / 1.1f;
        Canvas cb = new Canvas(bg);
        cb.scale(s, s);
        paint.setColor(Color.WHITE);
        playIcon.drawContainer(cb, c, c, radius, scale, paint);
        Canvas ci = new Canvas(icon);
        ci.scale(s, s);
        playIcon.setColorFilter(null);
        playIcon.drawIcon(ci, c, c, radius * 0.46f * scale, Color.WHITE, 255);
        return new Bitmap[]{bg, icon};
    }

    /**
     * Transparent margin around the cover inside its bitmap: the audio motion scales the cover up to ~1.095x,
     * shifts it by ~1.6 dp and tilts it ~1.2 deg. With 6 % on each side the moving cover always stays inside its
     * own ImageView, so the widget's padding never clips it: (1 - 2 * 0.06) * 1.095 = 0.96 < 1.
     */
    private static final float COVER_MOTION_MARGIN = 0.06f;

    private Bitmap renderCover(Context context, Colors c, int size, boolean hasMessage, boolean night) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        float inset = Build.VERSION.SDK_INT >= 31 ? size * COVER_MOTION_MARGIN : 0; // no motion below 12
        RectF r = new RectF(inset, inset, size - inset, size - inset);
        float content = size - 2 * inset;
        float corner = content * 0.2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (coverBitmap != null && !coverBitmap.isRecycled()) {
            BitmapShader shader = new BitmapShader(coverBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = Math.max(content / (float) coverBitmap.getWidth(), content / (float) coverBitmap.getHeight());
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate((size - coverBitmap.getWidth() * scale) / 2f, (size - coverBitmap.getHeight() * scale) / 2f);
            shader.setLocalMatrix(m);
            p.setShader(shader);
            canvas.drawRoundRect(r, corner, corner, p);
        } else {
            // no artwork / nothing playing: a vinyl record in the widget's palette (spins while playing)
            drawVinyl(canvas, r, c);
        }
        return out;
    }

    /**
     * Placeholder for tracks without artwork and for the empty state: a vinyl record in the widget's palette.
     * Dark body with fine concentric grooves (rotation-invariant), a soft sheen, an accent label with an inner
     * ring and two short "print" arcs (so the spin is visible) and a spindle hole in the surface colour.
     * Round with a transparent outside, so the widget can spin it with a plain view rotation.
     */
    private static void drawVinyl(Canvas canvas, RectF r, Colors c) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx = r.centerX(), cy = r.centerY(), R = r.width() / 2f;
        // body
        p.setShader(new android.graphics.RadialGradient(cx, cy, R, new int[]{c.discBody, c.discBody, c.discEdge}, new float[]{0f, 0.82f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, R * 0.985f, p);
        p.setShader(null);
        // rim: a thin accent-tinted edge so the disc separates from a dark widget surface
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1.5f, R * 0.03f));
        rim.setColor(c.discRim);
        canvas.drawCircle(cx, cy, R * 0.985f - rim.getStrokeWidth() / 2f, rim);
        // grooves
        Paint groove = new Paint(Paint.ANTI_ALIAS_FLAG);
        groove.setStyle(Paint.Style.STROKE);
        groove.setStrokeWidth(Math.max(1f, R * 0.008f));
        for (float k = 0.42f; k < 0.95f; k += 0.045f) {
            groove.setColor(c.discGroove);
            groove.setAlpha((int) (Color.alpha(c.discGroove) * (0.6f + 0.4f * (float) Math.sin(k * 37))));
            canvas.drawCircle(cx, cy, R * k, groove);
        }
        // sheen: two soft opposite highlights
        p.setShader(new android.graphics.SweepGradient(cx, cy,
                new int[]{0x00ffffff, c.discSheen, 0x00ffffff, 0x00ffffff, c.discSheen, 0x00ffffff, 0x00ffffff},
                new float[]{0.04f, 0.12f, 0.22f, 0.54f, 0.62f, 0.72f, 1f}));
        canvas.drawCircle(cx, cy, R * 0.96f, p);
        p.setShader(null);
        // label
        float L = R * 0.36f;
        p.setShader(new LinearGradient(cx - L, cy - L, cx + L, cy + L, c.accent, c.labelTo, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, L, p);
        p.setShader(null);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(c.labelInk);
        ring.setAlpha(110);
        ring.setStrokeWidth(Math.max(1f, L * 0.05f));
        canvas.drawCircle(cx, cy, L * 0.72f, ring);
        ring.setAlpha(170);
        ring.setStrokeCap(Paint.Cap.ROUND);
        ring.setStrokeWidth(Math.max(1.5f, L * 0.09f));
        RectF arc = new RectF(cx - L * 0.52f, cy - L * 0.52f, cx + L * 0.52f, cy + L * 0.52f);
        canvas.drawArc(arc, 200, 70, false, ring);
        ring.setStrokeWidth(Math.max(1f, L * 0.06f));
        canvas.drawArc(arc, 290, 25, false, ring);
        // spindle hole
        p.setColor(c.surface);
        canvas.drawCircle(cx, cy, L * 0.13f, p);
    }

    /** The cover's most characteristic colour (vibrant, else dominant), or 0. */
    private static int seedFrom(Bitmap bitmap) {
        try {
            androidx.palette.graphics.Palette palette = androidx.palette.graphics.Palette.from(bitmap).maximumColorCount(16).generate();
            androidx.palette.graphics.Palette.Swatch sw = palette.getVibrantSwatch();
            androidx.palette.graphics.Palette.Swatch dom = palette.getDominantSwatch();
            // prefer the vibrant colour only if it is reasonably present on the cover
            if (sw == null || dom != null && sw.getPopulation() < dom.getPopulation() / 6) sw = dom;
            return sw != null ? sw.getRgb() : 0;
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        }
    }

    // ---------------------------------------------------------------- cover

    /**
     * Same sources as the chat bubble / player notification: embedded tags, else the document thumbnail the
     * server attaches to the audio file (biggest available), else the iTunes-style artwork URL. Thumbnails
     * load asynchronously through an ImageReceiver; the widget refreshes when it arrives.
     */
    private void loadCover(MessageObject message) {
        coverBitmap = null;
        coverSeed = 0;
        if (coverReceiver == null) {
            coverReceiver = new ImageReceiver(null);
            coverReceiver.setDelegate((receiver, set, thumb, memCache) -> {
                if (set && !thumb) {
                    Bitmap b = receiver.getBitmap();
                    if (b != null && !b.isRecycled()) {
                        // copy: the receiver's bitmap can be recycled when it is reused
                        coverBitmap = b.copy(Bitmap.Config.ARGB_8888, false);
                        coverSeed = seedFrom(coverBitmap);
                        forceFull = true;
                    }
                }
            });
        }
        coverReceiver.setImageBitmap((Bitmap) null);
        if (message == null) {
            return;
        }
        try {
            AudioInfo info = MediaController.getInstance().getAudioInfo();
            if (info != null && info.getCover() != null) {
                coverBitmap = info.getCover();
                coverSeed = seedFrom(coverBitmap);
                return;
            }
            TLRPC.Document document = message.getDocument();
            if (document != null && MessageObject.isDocumentHasThumb(document)) {
                TLRPC.PhotoSize big = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 1000, true, null, true);
                TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 90, true, null, false);
                if (big != null) {
                    coverReceiver.setImage(ImageLocation.getForDocument(big, document), "400_400",
                            small != null && small != big ? ImageLocation.getForDocument(small, document) : null, "400_400",
                            null, 0, null, message, 1);
                    return;
                }
            }
            String url = message.getArtworkUrl(false);
            if (!TextUtils.isEmpty(url)) {
                coverReceiver.setImage(url, "400_400", null, null, 0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * The whole widget palette derived from one seed colour (the cover's, else the system accent): tones of the
     * same hue for surface / chips / accent / track / text, dark or light with the system theme.
     */
    private static final class Colors {
        final int surface, container, accent, onAccent, accentContainer, track, text, textSecondary;
        final int discBody, discEdge, discGroove, discSheen, discRim, labelTo, labelInk;

        Colors(Context ctx, boolean night, int seed) {
            if (seed == 0) {
                seed = Build.VERSION.SDK_INT >= 31 ? ctx.getColor(android.R.color.system_accent1_500) : 0xff2f7dd6;
            }
            float[] hsl = new float[3];
            androidx.core.graphics.ColorUtils.colorToHSL(seed, hsl);
            float h = hsl[0];
            float sat = hsl[1];
            boolean grey = sat < 0.12f || hsl[2] < 0.06f || hsl[2] > 0.96f; // b/w covers: stay neutral
            float sMain = grey ? 0.06f : Math.max(0.45f, Math.min(0.8f, sat));
            float sSoft = grey ? 0.04f : Math.max(0.18f, Math.min(0.4f, sat * 0.6f));
            if (night) {
                surface = hsl(h, sSoft, 0.12f);
                container = hsl(h, sSoft, 0.2f);
                accent = hsl(h, sMain, 0.76f);
                onAccent = hsl(h, sMain, 0.16f);
                accentContainer = hsl(h, sMain * 0.8f, 0.3f);
                track = hsl(h, sSoft, 0.34f);
                text = hsl(h, sSoft * 0.5f, 0.95f);
                textSecondary = hsl(h, sSoft * 0.6f, 0.72f);
                discBody = hsl(h, sSoft * 0.7f, 0.07f);
                discEdge = hsl(h, sSoft * 0.7f, 0.11f);
                discGroove = 0x14ffffff;
                discSheen = 0x2effffff;
                discRim = androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x70);
                labelTo = hsl(h + 38, sMain, 0.62f);
                labelInk = onAccent;
            } else {
                surface = hsl(h, sSoft, 0.94f);
                container = hsl(h, sSoft, 0.86f);
                accent = hsl(h, sMain, 0.4f);
                onAccent = 0xffffffff;
                accentContainer = hsl(h, sMain * 0.8f, 0.84f);
                track = hsl(h, sSoft, 0.76f);
                text = hsl(h, sSoft * 0.6f, 0.12f);
                textSecondary = hsl(h, sSoft * 0.6f, 0.36f);
                discBody = hsl(h, sSoft * 0.8f, 0.15f);
                discEdge = hsl(h, sSoft * 0.8f, 0.2f);
                discGroove = 0x18ffffff;
                discSheen = 0x38ffffff;
                discRim = androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x55);
                labelTo = hsl(h + 38, sMain, 0.5f);
                labelInk = 0xffffffff;
            }
        }

        private static int hsl(float h, float s, float l) {
            return androidx.core.graphics.ColorUtils.HSLToColor(new float[]{(h % 360 + 360) % 360, Math.max(0, Math.min(1, s)), Math.max(0, Math.min(1, l))});
        }
    }
}
