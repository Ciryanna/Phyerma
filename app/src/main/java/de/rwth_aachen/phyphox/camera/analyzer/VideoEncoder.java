package de.rwth_aachen.phyphox.camera.analyzer;

import static android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
import static de.rwth_aachen.phyphox.camera.analyzer.OpenGLHelper.buildProgram;
import static de.rwth_aachen.phyphox.camera.analyzer.OpenGLHelper.checkGLError;
import static de.rwth_aachen.phyphox.camera.analyzer.OpenGLHelper.fullScreenVertexShader;
import static de.rwth_aachen.phyphox.camera.analyzer.OpenGLHelper.fullScreenVboTexCoordinates;
import static de.rwth_aachen.phyphox.camera.analyzer.OpenGLHelper.fullScreenVboVertices;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Process;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

//VideoEncoder turns the GL pipeline's camera frames into an MP4 segment. It lives entirely on the
//AnalyzingOpenGLRenderer executor thread: begin/end encode calls are posted there, and draw() calls
//encodeFrame() inline, so every submitted frame carries the very camera timestamp that the rest of
//the pipeline already aligned to the monotonic clock (and through it to experiment time). The frame
//time is handed to the encoder via eglPresentationTimeANDROID, so the MP4's own presentation
//timestamps reproduce the same timeline - the file stays self-aligning even if the side data is lost.
//
//Optional audio: a second MediaCodec (AAC) fed by an AudioRecord on its own thread is muxed into
//the same file, giving the video a waveform that can double as an acoustic-sync carrier.
public class VideoEncoder {

    private static final String TAG = "VideoEncoder";

    final static String videoFragmentShader =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES texture;\n" +
            "varying vec2 texPosition;\n" +
            "varying vec2 positionInPassepartout;\n" +
            "void main () {\n" +
            "  gl_FragColor = texture2D(texture, texPosition);\n" +
            "}\n";

    private final EGLDisplay eglDisplay;
    private final EGLConfig eglConfig;
    private final EGLContext eglContext;
    private final int cameraTexture;

    private MediaCodec videoCodec;
    private Surface codecInputSurface;
    private EGLSurface eglSurface;
    private MediaMuxer muxer;
    private File currentFile;
    private int program = 0;
    private int verticesHandle, texCoordinatesHandle, camMatrixHandle, textureHandle, passepartoutMinHandle, passepartoutMaxHandle;

    private volatile boolean recording = false;
    private volatile boolean muxerStarted = false;
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    private final Object muxerLock = new Object();

    private volatile long segmentStartNanos = 0; //first frame's timestamp, already adjusted to the elapsed domain
    private long framesSubmitted = 0;
    private long framesWritten = 0;
    private long packetsSkipped = 0;
    private int encWidth = 0;
    private int encHeight = 0;
    private final java.util.PriorityQueue<PendingPacket> pendingPackets = new java.util.PriorityQueue<>();
    //How far behind the newest packet a queued packet may lag before it is released to the muxer.
    //3 s covers deep encoder lookahead without much memory (~90 video + ~140 audio packets).
    private static final long REORDER_US = 3_000_000L;
    private long maxEnqueuedPts = Long.MIN_VALUE;
    private final java.util.List<Long> writtenNanos = new java.util.ArrayList<>();

    //Optional AAC track
    private final boolean withAudio;
    private MediaCodec audioCodec;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private static final int AUDIO_RATE = 48000;

    public VideoEncoder(EGLDisplay eglDisplay, EGLConfig eglConfig, EGLContext eglContext, int cameraTexture, boolean withAudio) {
        this.eglDisplay = eglDisplay;
        this.eglConfig = eglConfig;
        this.eglContext = eglContext;
        this.cameraTexture = cameraTexture;
        this.withAudio = withAudio;
    }

    public boolean isRecording() {
        return recording;
    }

    public long getFramesWritten() {
        return framesWritten;
    }

    public long getFramesSubmitted() {
        return framesSubmitted;
    }

    public long getPacketsSkipped() {
        return packetsSkipped;
    }


    //GL thread. Starts a new segment file. Returns false if the encoder could not be brought up -
    //the caller surfaces that as "video unavailable" while the experiment keeps running.
    public boolean beginSegment(File file, int width, int height, int fps) {
        if (recording)
            endSegment();
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(width, height));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            //Baseline = no B-frames. On devices whose encoder reorders frames (lookahead buffering),
            //the packets come out in decode order and their presentation times are no longer
            //monotonic - MediaMuxer then rejects writes with "timestamp must be monotonically
            //increasing" and the segment loses its first frames silently. Baseline profile keeps
            //decode order == presentation order, so timestamps always increase.
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_LATENCY, 0);

            videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            videoCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codecInputSurface = videoCodec.createInputSurface();
            videoCodec.start();

            int[] surfaceAttribs = {EGL14.EGL_NONE};
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, codecInputSurface, surfaceAttribs, 0);
            if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE)
                throw new IOException("Could not create the encoder EGL surface. This device may lack EGL_RECORDABLE_ANDROID.");

            if (program == 0) {
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
                program = buildProgram(fullScreenVertexShader, videoFragmentShader);
                verticesHandle = GLES20.glGetAttribLocation(program, "vertices");
                texCoordinatesHandle = GLES20.glGetAttribLocation(program, "texCoordinates");
                camMatrixHandle = GLES20.glGetUniformLocation(program, "camMatrix");
                textureHandle = GLES20.glGetUniformLocation(program, "texture");
                passepartoutMinHandle = GLES20.glGetUniformLocation(program, "passepartoutMin");
                passepartoutMaxHandle = GLES20.glGetUniformLocation(program, "passepartoutMax");
                checkGLError("video encoder: prepareOpenGL");
            }

            muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            currentFile = file;
            encWidth = width;
            encHeight = height;
            videoTrackIndex = -1;
            audioTrackIndex = -1;
            muxerStarted = false;
            framesSubmitted = 0;
            framesWritten = 0;
            packetsSkipped = 0;
            maxEnqueuedPts = Long.MIN_VALUE;
            writtenNanos.clear();
            pendingPackets.clear();
            segmentStartNanos = -1;

            if (withAudio)
                startAudioTrack();

            recording = true;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Could not start video encoding.", e);
            cleanupPartial();
            return false;
        }
    }

    private int bitrateFor(int width, int height) {
        //Roughly 0.15 bit per pixel per frame at 30fps - middle ground between detail and size.
        return (int) (width * height * 0.15 * 30);
    }

    //GL thread, called from draw() once per camera frame while recording. Returns true only when
    //a frame was actually handed to the encoder - the caller uses it to keep the `t` buffer in
    //strict 1:1 correspondence with the MP4's frames.
    public boolean encodeFrame(float[] camMatrix, long frameNanos) {
        if (!recording)
            return false;
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE)
            return false;

        if (segmentStartNanos < 0)
            segmentStartNanos = frameNanos;

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "eglMakeCurrent failed for encoder surface.");
            return false;
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        //The analysis passes leave a much smaller viewport bound to this context - without this
        //the video would be rendered into their stale viewport and most of the frame stay black.
        GLES20.glViewport(0, 0, encWidth, encHeight);

        GLES20.glUseProgram(program);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, fullScreenVboVertices);
        GLES20.glEnableVertexAttribArray(verticesHandle);
        GLES20.glVertexAttribPointer(verticesHandle, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, fullScreenVboTexCoordinates);
        GLES20.glEnableVertexAttribArray(texCoordinatesHandle);
        GLES20.glVertexAttribPointer(texCoordinatesHandle, 2, GLES20.GL_FLOAT, false, 0, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, cameraTexture);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glUniform1i(textureHandle, 0);
        GLES20.glUniform2f(passepartoutMinHandle, 0f, 0f);
        GLES20.glUniform2f(passepartoutMaxHandle, 1f, 1f);
        GLES20.glUniformMatrix4fv(camMatrixHandle, 1, false, camMatrix, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(verticesHandle);
        GLES20.glDisableVertexAttribArray(texCoordinatesHandle);

        //Presentation time in nanoseconds, relative to this segment's first frame. MediaCodec
        //turns it into the MP4's presentationTimeUs.
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, frameNanos - segmentStartNanos);
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);

        framesSubmitted++;
        drainVideo(false);
        checkGLError("video encoder: draw");
        return true;
    }

    //GL thread. Finishes the current segment.
    public void endSegment() {
        if (!recording)
            return;
        recording = false;

        try {
            videoCodec.signalEndOfInputStream();
            drainVideo(true);
        } catch (Exception e) {
            Log.e(TAG, "Could not drain the video encoder.", e);
        }
        stopAudioTrack();
        //Only now - after both tracks delivered their remaining packets - flush the shared
        //queue so the muxer receives every sample in globally increasing presentation order.
        synchronized (muxerLock) {
            flushPendingPackets(true);
        }

        synchronized (muxerLock) {
            try {
                if (muxerStarted)
                    muxer.stop();
                muxer.release();
            } catch (Exception e) {
                Log.e(TAG, "Could not finalize the video file.", e);
            }
        }

        try {
            videoCodec.stop();
            videoCodec.release();
        } catch (Exception ignored) {
        }
        videoCodec = null;

        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, eglContext);
        if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE)
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
        eglSurface = null;
        if (codecInputSurface != null)
            codecInputSurface.release();
        codecInputSurface = null;
        muxer = null;
    }

    //GL thread. Pulls whatever the encoder produced since the last call. With block=false this is
    //just bookkeeping between frames; with block=true (segment end) it waits for the EOS flag so
    //the tail of the segment is not truncated - a slow encoder may take several polls to deliver
    //the remaining frames.
    private void drainVideo(boolean block) {
        if (videoCodec == null)
            return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (true) {
            int index;
            try {
                index = videoCodec.dequeueOutputBuffer(info, block ? 10000 : 0);
            } catch (IllegalStateException e) {
                break;
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!block || System.nanoTime() > deadline)
                    break;
                continue;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (muxerLock) {
                    videoTrackIndex = muxer.addTrack(videoCodec.getOutputFormat());
                    maybeStartMuxer();
                }
                continue;
            }
            ByteBuffer buffer;
            try {
                buffer = videoCodec.getOutputBuffer(index);
            } catch (IllegalStateException e) {
                break;
            }
            if (buffer != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                //Copy the packet out of the codec buffer and queue it. Encoders with internal
                //lookahead emit packets out of presentation order even when they accept the
                //baseline-profile hint, and on some devices the muxer silently drops samples
                //whose presentation time falls behind the other track - so both tracks share
                //one priority queue and go out in globally increasing presentation order.
                byte[] copy = new byte[info.size];
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                buffer.get(copy);
                synchronized (muxerLock) {
                    enqueuePacket(TRACK_VIDEO, info.presentationTimeUs, info.flags, copy);
                }
            }
            try {
                videoCodec.releaseOutputBuffer(index, false);
            } catch (IllegalStateException e) {
                break;
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                synchronized (muxerLock) {
                    flushPendingPackets(true);
                }
                break;
            }
        }
    }

    //Caller holds muxerLock. Buffers a packet and releases the queue's head once the newest
    //packet seen is REORDER_US ahead of it - packets older than that cannot be overtaken any
    //more because each track emits its own timestamps monotonically.
    private void enqueuePacket(int track, long ptsUs, int flags, byte[] data) {
        pendingPackets.add(new PendingPacket(track, ptsUs, flags, data));
        maxEnqueuedPts = Math.max(maxEnqueuedPts, ptsUs);
        while (!pendingPackets.isEmpty() && pendingPackets.peek().ptsUs <= maxEnqueuedPts - REORDER_US)
            writePendingPacket(pendingPackets.poll());
    }

    //Caller holds muxerLock. With all=true the whole queue drains sorted - used at segment end.
    //Until the muxer has started (both tracks announced), packets stay queued - the audio
    //codec's format change can take about a second and dropping its packets would cut off the
    //segment's beginning, as seen on vivo where the first ~30 video frames went missing.
    private void flushPendingPackets(boolean all) {
        if (!muxerStarted)
            return;
        while (!pendingPackets.isEmpty() && (all || pendingPackets.peek().ptsUs <= maxEnqueuedPts - REORDER_US))
            writePendingPacket(pendingPackets.poll());
    }

    //Caller holds muxerLock.
    private void writePendingPacket(PendingPacket p) {
        int trackIndex = p.track == TRACK_VIDEO ? videoTrackIndex : audioTrackIndex;
        if (trackIndex < 0)
            return; //Track not announced yet - should not happen, packet arrived before format
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, p.data.length, p.ptsUs, p.flags);
        try {
            muxer.writeSampleData(trackIndex, ByteBuffer.wrap(p.data), info);
            if (p.track == TRACK_VIDEO) {
                framesWritten++;
                //The packet's PTS is the submitted frame's monotonic stamp minus the segment
                //offset - hand the absolute timestamp to the caller so the `t` buffer only lists
                //frames that actually made it into the MP4 (row N == video frame N even on
                //devices whose encoder drops inputs).
                writtenNanos.add(p.ptsUs * 1000 + segmentStartNanos);
            }
        } catch (Exception e) {
            //A rejected packet must not kill the drain - count it so video.json reports it.
            if (p.track == TRACK_VIDEO)
                packetsSkipped++;
            Log.w(TAG, "muxer rejected a packet: " + e.getMessage());
        }
    }

    private static final int TRACK_VIDEO = 0;
    private static final int TRACK_AUDIO = 1;

    //GL thread. Pops the monotonic timestamps of the video frames written since the last call.
    //Encoders drop input frames silently (surface input), so this list - not the submission
    //count - is what corresponds to the MP4's frame index.
    public void pollWrittenNanos(java.util.List<Long> out) {
        out.addAll(writtenNanos);
        writtenNanos.clear();
    }

    private static class PendingPacket implements Comparable<PendingPacket> {
        final int track;
        final long ptsUs;
        final int flags;
        final byte[] data;

        PendingPacket(int track, long ptsUs, int flags, byte[] data) {
            this.track = track;
            this.ptsUs = ptsUs;
            this.flags = flags;
            this.data = data;
        }

        @Override
        public int compareTo(PendingPacket other) {
            return Long.compare(ptsUs, other.ptsUs);
        }
    }

    private void maybeStartMuxer() {
        //Caller holds muxerLock. Audio needs its track, too, before the muxer can start.
        int expectedTracks = withAudio && audioCodec != null ? 2 : 1;
        if (!muxerStarted && videoTrackIndex >= 0 && (expectedTracks == 1 || audioTrackIndex >= 0)) {
            try {
                muxer.start();
                muxerStarted = true;
                //Packets queued while the muxer waited for all track formats can go out now.
                flushPendingPackets(false);
            } catch (Exception e) {
                Log.e(TAG, "Could not start the muxer.", e);
            }
        }
    }

    private void startAudioTrack() {
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audioCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioCodec.start();

            int minBuffer = AudioRecord.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuffer, AUDIO_RATE * 2));
            audioRecord.startRecording();

            audioThread = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                short[] samples = new short[2048];
                long samplesFed = 0;
                long audioBaseSamples = -1; //first sample index that lands on the video timeline
                long audioBaseNanos = 0;    //pts (ns) of that sample on the shared timeline
                while (audioThread != null && Thread.currentThread() == audioThread) {
                    int n;
                    try {
                        n = audioRecord.read(samples, 0, samples.length);
                    } catch (Exception e) {
                        break;
                    }
                    if (n <= 0)
                        continue;
                    if (segmentStartNanos < 0) {
                        //Waiting for video frame 0 (the shared timeline origin). Samples read
                        //while waiting sat in the HAL buffer since the stream started - their
                        //capture time cannot be reconstructed, so they are discarded wholesale
                        //rather than fed with a wrong presentation time.
                        samplesFed += n;
                        continue;
                    }
                    if (audioBaseSamples < 0) {
                        audioBaseSamples = samplesFed;
                        long now = android.os.SystemClock.elapsedRealtimeNanos();
                        long anchor = now;
                        try {
                            android.media.AudioTimestamp ats = new android.media.AudioTimestamp();
                            if (audioRecord.getTimestamp(ats, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                                //nanoTime is in a vendor-specific domain on some devices, but
                                //framePosition (HAL frames delivered so far) stays truthful:
                                //it gives the buffer occupancy, hence the capture time of the
                                //sample we are about to feed.
                                long occupancy = Math.max(0, ats.framePosition - samplesFed);
                                anchor = now - occupancy * 1_000_000_000L / AUDIO_RATE;
                            }
                        } catch (Exception ignored) {
                        }
                        audioBaseNanos = anchor - segmentStartNanos;
                    }
                    feedAudioCodec(samples, n, samplesFed - audioBaseSamples, audioBaseNanos);
                    samplesFed += n;
                }
                //EOS once the loop exits (stopAudioTrack cleared the thread reference)
                feedAudioCodec(null, 0, Math.max(0, samplesFed - Math.max(audioBaseSamples, 0)), audioBaseNanos);
            }, "VideoEncoder-Audio");
            audioThread.start();
        } catch (Exception e) {
            Log.e(TAG, "Audio track unavailable, recording video only.", e);
            audioCodec = null;
            audioRecord = null;
            audioThread = null;
        }
    }

    private void feedAudioCodec(short[] samples, int count, long samplesBefore, long audioBaseNanos) {
        MediaCodec codec = audioCodec;
        if (codec == null)
            return;
        //Sample-clock pts on the shared video timeline: audioBaseNanos is the pts of the
        //first post-frame-0 sample. Pre-video chunks carry negative pts and are skipped.
        long ptsUs = audioBaseNanos / 1_000 + samplesBefore * 1_000_000L / AUDIO_RATE;
        if (ptsUs < 0)
            return;
        try {
            int inIndex = codec.dequeueInputBuffer(10000);
            if (inIndex >= 0) {
                ByteBuffer in = codec.getInputBuffer(inIndex);
                in.clear();
                for (int i = 0; i < count; i++)
                    in.putShort(samples[i]);
                codec.queueInputBuffer(inIndex, 0, count * 2, ptsUs, samples == null ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
            }
            drainAudio(false);
        } catch (Exception e) {
            Log.e(TAG, "Audio encode failed mid-segment.", e);
        }
    }

    private void drainAudio(boolean block) {
        MediaCodec codec = audioCodec;
        if (codec == null)
            return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int index;
            try {
                index = codec.dequeueOutputBuffer(info, block ? 10000 : 0);
            } catch (IllegalStateException e) {
                break;
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER)
                break;
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (muxerLock) {
                    audioTrackIndex = muxer.addTrack(codec.getOutputFormat());
                    maybeStartMuxer();
                }
                continue;
            }
            ByteBuffer buffer;
            try {
                buffer = codec.getOutputBuffer(index);
            } catch (IllegalStateException e) {
                break;
            }
            if (buffer != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                byte[] copy = new byte[info.size];
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                buffer.get(copy);
                synchronized (muxerLock) {
                    enqueuePacket(TRACK_AUDIO, info.presentationTimeUs, info.flags, copy);
                }
            }
            try {
                codec.releaseOutputBuffer(index, false);
            } catch (IllegalStateException e) {
                break;
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                break;
        }
    }

    private void stopAudioTrack() {
        Thread t = audioThread;
        audioThread = null;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException ignored) {
            }
        }
        if (t != null) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (audioCodec != null) {
            drainAudio(true);
            try {
                audioCodec.stop();
                audioCodec.release();
            } catch (Exception ignored) {
            }
            audioCodec = null;
        }
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
    }

    private void cleanupPartial() {
        if (audioCodec != null) {
            try {
                audioCodec.stop();
                audioCodec.release();
            } catch (Exception ignored) {
            }
            audioCodec = null;
        }
        if (videoCodec != null) {
            try {
                videoCodec.stop();
                videoCodec.release();
            } catch (Exception ignored) {
            }
            videoCodec = null;
        }
        if (codecInputSurface != null) {
            codecInputSurface.release();
            codecInputSurface = null;
        }
        if (muxer != null) {
            try {
                muxer.release();
            } catch (Exception ignored) {
            }
            muxer = null;
        }
        if (currentFile != null && currentFile.exists() && currentFile.length() == 0)
            currentFile.delete();
        currentFile = null;
    }
}
