package org.telegram.ui.Components;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Joins consecutive MP4 recordings made by the AAR after a pause. Runs off the UI thread. */
final class VideoMessageSegmentMerger {
    private VideoMessageSegmentMerger() {}

    static void merge(File first, File second, File output) throws IOException {
        MediaExtractor firstExtractor = new MediaExtractor();
        MediaExtractor secondExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean finished = false;
        try {
            firstExtractor.setDataSource(first.getAbsolutePath());
            secondExtractor.setDataSource(second.getAbsolutePath());
            int firstVideo = findTrack(firstExtractor, "video/");
            int firstAudio = findTrack(firstExtractor, "audio/");
            int secondVideo = findTrack(secondExtractor, "video/");
            int secondAudio = findTrack(secondExtractor, "audio/");
            if (firstVideo < 0 || secondVideo < 0 || (firstAudio < 0) != (secondAudio < 0)) {
                throw new IOException("Video segments have different tracks");
            }
            checkFormat(firstExtractor.getTrackFormat(firstVideo), secondExtractor.getTrackFormat(secondVideo), true);
            if (firstAudio >= 0) {
                checkFormat(firstExtractor.getTrackFormat(firstAudio), secondExtractor.getTrackFormat(secondAudio), false);
            }
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int rotation = rotation(first);
            if (rotation != 0) muxer.setOrientationHint(rotation);
            int videoOut = muxer.addTrack(firstExtractor.getTrackFormat(firstVideo));
            int audioOut = firstAudio < 0 ? -1 : muxer.addTrack(firstExtractor.getTrackFormat(firstAudio));
            muxer.start();
            ByteBuffer buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024);
            long nextStartUs = copySegment(firstExtractor, firstVideo, firstAudio, muxer,
                    videoOut, audioOut, buffer, 0L);
            copySegment(secondExtractor, secondVideo, secondAudio, muxer,
                    videoOut, audioOut, buffer, nextStartUs);
            muxer.stop();
            finished = true;
        } finally {
            firstExtractor.release();
            secondExtractor.release();
            if (muxer != null) {
                if (!finished) {
                    try { muxer.stop(); } catch (Exception ignored) {}
                }
                muxer.release();
            }
            if (!finished) output.delete();
        }
    }

    private static long copySegment(MediaExtractor extractor, int videoTrack, int audioTrack,
                                    MediaMuxer muxer, int videoOut, int audioOut,
                                    ByteBuffer buffer, long startUs) throws IOException {
        long firstUs = Long.MAX_VALUE;
        for (int track : new int[]{videoTrack, audioTrack}) {
            if (track < 0) continue;
            extractor.selectTrack(track);
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            if (extractor.getSampleTime() >= 0) firstUs = Math.min(firstUs, extractor.getSampleTime());
            extractor.unselectTrack(track);
        }
        if (firstUs == Long.MAX_VALUE) throw new IOException("Empty video segment");
        long lastUs = startUs;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        for (int track : new int[]{videoTrack, audioTrack}) {
            if (track < 0) continue;
            extractor.selectTrack(track);
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            while (extractor.getSampleTime() >= 0) {
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                if (size > buffer.capacity()) throw new IOException("MP4 sample exceeds merge buffer");
                long timeUs = startUs + extractor.getSampleTime() - firstUs;
                info.set(0, size, timeUs, extractor.getSampleFlags());
                muxer.writeSampleData(track == videoTrack ? videoOut : audioOut, buffer, info);
                lastUs = Math.max(lastUs, timeUs);
                extractor.advance();
            }
            extractor.unselectTrack(track);
        }
        return lastUs + 33_333L;
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static void checkFormat(MediaFormat a, MediaFormat b, boolean video) throws IOException {
        if (!a.getString(MediaFormat.KEY_MIME).equals(b.getString(MediaFormat.KEY_MIME))
                || video && (a.getInteger(MediaFormat.KEY_WIDTH) != b.getInteger(MediaFormat.KEY_WIDTH)
                || a.getInteger(MediaFormat.KEY_HEIGHT) != b.getInteger(MediaFormat.KEY_HEIGHT))
                || !video && (a.getInteger(MediaFormat.KEY_SAMPLE_RATE) != b.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                || a.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != b.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                || !sameCodecData(a, b, "csd-0")
                || video && !sameCodecData(a, b, "csd-1")) {
            throw new IOException("Video segment format changed after pause");
        }
    }

    private static boolean sameCodecData(MediaFormat a, MediaFormat b, String key) {
        ByteBuffer left = a.getByteBuffer(key);
        ByteBuffer right = b.getByteBuffer(key);
        if (left == null || right == null) return left == right;
        return left.asReadOnlyBuffer().equals(right.asReadOnlyBuffer());
    }

    private static int rotation(File file) {
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            metadata.setDataSource(file.getAbsolutePath());
            String value = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            return value == null ? 0 : Integer.parseInt(value);
        } catch (Exception ignored) {
            return 0;
        } finally {
            try { metadata.release(); } catch (Exception ignored) {}
        }
    }
}
