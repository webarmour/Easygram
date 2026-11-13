package org.telegram.messenger;

import android.os.SystemClock;

/**
 * Drum-hit / energy follower for the currently playing audio (used by the Material 3 Expressive UI).
 *
 * Fed from the audio player's FFT tap ({@code VideoPlayer.VisualizerBufferSink}) through
 * {@link DrumOnsetDetector}: separate kick / snare / hat hits plus a continuous bass + loudness level.
 * Results are queued with their media time and applied when the player actually plays that moment.
 *
 * {@link #getPulse()} is the combined "bump": a kick pushes hardest and longest, a snare clearly but shorter,
 * hats only add a light flicker. {@link #getEnergy()} lets elements breathe with the loudness.
 *
 * The analysis only runs while some M3E element is visible and asks for it ({@link #markInterest()}).
 * All public methods are called on the main thread.
 */
public class MusicBeatTracker {

    private static final MusicBeatTracker instance = new MusicBeatTracker();

    public static MusicBeatTracker getInstance() {
        return instance;
    }

    private static final long INTEREST_TIMEOUT_MS = 1500;

    // per instrument: weight in the combined pulse and decay time
    private static final float[] WEIGHT = {1.0f, 0.65f, 0.2f};   // kick, snare, hat
    private static final float[] DECAY_MS = {170f, 120f, 70f};
    private static final int INSTRUMENTS = 3;

    private volatile long lastInterestTime;

    private float energy;
    private long lastFrameTime;
    private final long[] hitTime = new long[INSTRUMENTS];
    private final float[] hitStrength = new float[INSTRUMENTS];

    // analysis results waiting for their moment in the audible playback timeline
    private static final int QUEUE_MAX = 512;
    private final long[] qTime = new long[QUEUE_MAX];
    private final float[] qLevel = new float[QUEUE_MAX];
    private final float[] qBass = new float[QUEUE_MAX];
    private float bass;
    private final float[][] qHits = new float[QUEUE_MAX][INSTRUMENTS];
    private int qHead, qSize;
    private int generation = -1;
    private long basePositionMs = -1;

    /** UI elements call this every frame they want beat data; keeps the analysis enabled. */
    public void markInterest() {
        lastInterestTime = SystemClock.elapsedRealtime();
    }

    /** Whether the audio thread should spend time feeding us. Any thread. */
    public boolean isActive() {
        return SystemClock.elapsedRealtime() - lastInterestTime < INTEREST_TIMEOUT_MS;
    }

    /** Main thread: the audio stream was flushed (start / seek); anchor the timeline to the player position. */
    public void onStreamReset(org.telegram.ui.Components.VideoPlayer source, int generation) {
        if (!MediaController.getInstance().isCurrentAudioPlayer(source)) {
            return; // flush of a released / previous player
        }
        this.generation = generation;
        long position = MediaController.getInstance().getAudioPlayerPositionMs();
        this.basePositionMs = Math.max(0, position);
        qHead = 0;
        qSize = 0;
    }

    /** Main thread: an analysis window at {@code offsetMs} of media time since the last stream reset. */
    public void enqueue(int generation, long offsetMs, float level, float bassLevel, float kick, float snare, float hat) {
        if (generation != this.generation || basePositionMs < 0) {
            return;
        }
        if (qSize == QUEUE_MAX) { // drop oldest
            qHead = (qHead + 1) % QUEUE_MAX;
            qSize--;
        }
        int i = (qHead + qSize) % QUEUE_MAX;
        qTime[i] = basePositionMs + offsetMs;
        qLevel[i] = level;
        qBass[i] = bassLevel;
        qHits[i][0] = kick;
        qHits[i][1] = snare;
        qHits[i][2] = hat;
        qSize++;
    }

    /** Applies every queued window whose media time has been reached by the audible playback position. */
    private void pump() {
        if (qSize == 0) return;
        long position = MediaController.getInstance().getAudioPlayerPositionMs();
        if (position < 0) return;
        // Self-heal: the decoder is never more than ~1-2 s ahead of what is heard. A head far in the future
        // means the anchor is wrong (player switched / position read too early) -> re-anchor to "now".
        long ahead = qTime[qHead] - position;
        if (ahead > 2500) {
            for (int k = 0, i = qHead; k < qSize; k++, i = (i + 1) % QUEUE_MAX) {
                qTime[i] -= ahead;
            }
            basePositionMs -= ahead;
        }
        long now = SystemClock.elapsedRealtime();
        while (qSize > 0 && qTime[qHead] <= position) {
            long late = position - qTime[qHead];
            lastFrameTime = now;
            // fast attack, slower release
            float level = qLevel[qHead];
            energy += (level - energy) * (level > energy ? 0.5f : 0.15f);
            float b = qBass[qHead];
            bass += (b - bass) * (b > bass ? 0.35f : 0.2f);
            if (late <= 250) { // too late to show a hit -> keep only the level
                float[] hits = qHits[qHead];
                for (int k = 0; k < INSTRUMENTS; k++) {
                    if (hits[k] > 0) {
                        // the hit happened `late` ms ago in the audio; age the pulse accordingly
                        hitTime[k] = now - late;
                        hitStrength[k] = hits[k];
                    }
                }
            }
            qHead = (qHead + 1) % QUEUE_MAX;
            qSize--;
        }
    }

    /** 0..1 pulse of one instrument ({@link DrumOnsetDetector#KICK}, SNARE, HAT). */
    public float getHit(int instrument) {
        pump();
        return hitValue(instrument, SystemClock.elapsedRealtime());
    }

    private float hitValue(int k, long now) {
        if (hitTime[k] == 0) return 0f;
        long t = now - hitTime[k];
        if (t < 0) t = 0;
        if (t > 1000) return 0f;
        return hitStrength[k] * (float) Math.exp(-t / DECAY_MS[k]);
    }

    /** 0..1 combined bump: kick strongest, snare medium, hats a light flicker. */
    public float getPulse() {
        pump();
        long now = SystemClock.elapsedRealtime();
        float sum = 0;
        for (int k = 0; k < INSTRUMENTS; k++) {
            sum += WEIGHT[k] * hitValue(k, now);
        }
        return Math.min(1f, sum);
    }

    /**
     * 0..1 "bass buzz": how heavy the low end (40..160 Hz) is right now, by absolute level, so only a really
     * heavy bass (sustained 808, saw bass) makes elements tremble and a quiet intro bass doesn't.
     */
    public float getBassTremble() {
        pump();
        long stale = SystemClock.elapsedRealtime() - lastFrameTime;
        float v = Math.max(0f, Math.min(1f, bass)); // already gated by absolute bass level on the audio thread
        if (stale > 300) {
            v *= Math.max(0f, 1f - (stale - 300) / 300f);
        }
        return v;
    }

    /** 0..1: smoothed loudness relative to the recent peak; fades out when no audio is analysed. */
    public float getEnergy() {
        pump();
        long stale = SystemClock.elapsedRealtime() - lastFrameTime;
        if (stale > 300) {
            return Math.max(0f, energy * (1f - (stale - 300) / 500f));
        }
        return energy;
    }
}
