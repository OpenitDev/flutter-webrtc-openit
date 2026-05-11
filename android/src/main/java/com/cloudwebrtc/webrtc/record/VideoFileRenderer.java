// Modifications by Signify, Copyright 2025, Signify Holding -  SPDX-License-Identifier: MIT

package com.cloudwebrtc.webrtc.record;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import org.webrtc.EglBase;
import org.webrtc.GlRectDrawer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoFrameDrawer;
import org.webrtc.VideoSink;
import org.webrtc.audio.JavaAudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule.SamplesReadyCallback;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import android.media.MediaCodecList;

class VideoFileRenderer implements VideoSink, SamplesReadyCallback {
    private static final String TAG = "VideoFileRenderer";
    private final HandlerThread renderThread;
    private final Handler renderThreadHandler;
    private final HandlerThread audioThread;
    private final Handler audioThreadHandler;
    private int outputFileWidth = -1;
    private int outputFileHeight = -1;
    private ByteBuffer[] encoderOutputBuffers;
    private ByteBuffer[] audioInputBuffers;
    private ByteBuffer[] audioOutputBuffers;
    private EglBase eglBase;
    private final EglBase.Context sharedContext;
    private VideoFrameDrawer frameDrawer;

    // TODO: these ought to be configurable as well
    private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
    private static final int FRAME_RATE = 30;               // 30fps
    private static final int IFRAME_INTERVAL = 5;           // 5 seconds between I-frames

    private final MediaMuxer mediaMuxer;
    private MediaCodec encoder;
    private final MediaCodec.BufferInfo bufferInfo;
    private MediaCodec.BufferInfo audioBufferInfo;
    private volatile int trackIndex = -1;
    private volatile int audioTrackIndex;
    private boolean isRunning = true;
    private GlRectDrawer drawer;
    private Surface surface;
    private MediaCodec audioEncoder;
    // Name of the first encoder that advertises COLOR_FormatSurface support.
    // On Qualcomm devices OMX.qcom.video.encoder.avc rejects COLOR_FormatSurface
    // with error -38, so we must explicitly select a surface-capable codec.
    private String preferredEncoderName = null;

    /**
     * Returns the name of an encoder that both advertises and actually accepts
     * COLOR_FormatSurface at configure time, or null if none is found.
     *
     * Some HW encoders (e.g. OMX.qcom.video.encoder.avc on Qualcomm/Xiaomi) claim
     * COLOR_FormatSurface in their caps but reject it at configure time (error -38).
     * We perform a lightweight test-configure to verify real support before committing.
     *
     * Uses ALL_CODECS to expose SW encoders (e.g. c2.android.avc.encoder) that may
     * not appear in REGULAR_CODECS on some vendor ROMs.
     * SW encoders (c2.android.*, OMX.google.*) are tried before HW encoders.
     */
    private static String findEncoderSupportingSurface() {
        MediaCodecInfo[] codecs;
        try {
            codecs = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        } catch (Exception e) {
            Log.w(TAG, "Error getting codec list: " + e.getMessage());
            return null;
        }

        List<String> swCandidates = new ArrayList<>();
        List<String> hwCandidates = new ArrayList<>();

        for (MediaCodecInfo info : codecs) {
            if (!info.isEncoder()) continue;
            boolean supportsMime = false;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(MIME_TYPE)) { supportsMime = true; break; }
            }
            if (!supportsMime) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MIME_TYPE);
                boolean hasSurface = false;
                for (int fmt : caps.colorFormats) {
                    if (fmt == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
                        hasSurface = true;
                        break;
                    }
                }
                if (!hasSurface) continue;
                String name = info.getName();
                boolean isSoftware = name.startsWith("c2.android.") || name.startsWith("OMX.google.");
                if (isSoftware) {
                    swCandidates.add(name);
                } else {
                    hwCandidates.add(name);
                }
            } catch (Exception ignored) {}
        }

        // Try SW first, then HW — verify each with a real configure test
        List<String> ordered = new ArrayList<>(swCandidates);
        ordered.addAll(hwCandidates);

        for (String name : ordered) {
            if (testSurfaceEncoderConfigure(name)) {
                boolean sw = name.startsWith("c2.android.") || name.startsWith("OMX.google.");
                Log.d(TAG, "Found " + (sw ? "SW" : "HW") + " encoder supporting COLOR_FormatSurface: " + name);
                return name;
            }
        }

        Log.w(TAG, "No working encoder found for COLOR_FormatSurface — falling back to createEncoderByType");
        return null;
    }

    /**
     * Attempts a minimal configure with COLOR_FormatSurface to verify the codec
     * actually accepts surface input (not just claims to). Returns true on success.
     */
    private static boolean testSurfaceEncoderConfigure(String codecName) {
        MediaCodec testCodec = null;
        try {
            testCodec = MediaCodec.createByCodecName(codecName);
            MediaFormat testFormat = MediaFormat.createVideoFormat(MIME_TYPE, 320, 240);
            testFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            testFormat.setInteger(MediaFormat.KEY_BIT_RATE, 500000);
            testFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
            testFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            testCodec.configure(testFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            return true; // configure succeeded — codec is usable
        } catch (Exception e) {
            Log.d(TAG, "Encoder " + codecName + " failed surface configure test: " + e.getMessage());
            return false;
        } finally {
            if (testCodec != null) {
                try { testCodec.release(); } catch (Exception ignored) {}
            }
        }
    }

    VideoFileRenderer(String outputFile, final EglBase.Context sharedContext, boolean withAudio) throws IOException {
        renderThread = new HandlerThread(TAG + "RenderThread");
        renderThread.start();
        renderThreadHandler = new Handler(renderThread.getLooper());
        if (withAudio) {
            audioThread = new HandlerThread(TAG + "AudioThread");
            audioThread.start();
            audioThreadHandler = new Handler(audioThread.getLooper());
        } else {
            audioThread = null;
            audioThreadHandler = null;
        }
        bufferInfo = new MediaCodec.BufferInfo();
        this.sharedContext = sharedContext;

        // Create a MediaMuxer.  We can't add the video track and start() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the encoder after it has started processing data.
        mediaMuxer = new MediaMuxer(outputFile,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        audioTrackIndex = withAudio ? -1 : 0;
    }
    private boolean tryConfigureEncoder(EncoderConfig config) {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, config.width, config.height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
            // Use YUV420 semi-planar size (1.5 bytes per pixel) to reduce memory usage
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, config.width * config.height * 3 / 2);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);

            Log.d(TAG, "Trying encoder config: " + config);

            if (preferredEncoderName != null) {
                encoder = MediaCodec.createByCodecName(preferredEncoderName);
            } else {
                encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            }
            String codecName = encoder.getName();
            Log.d(TAG, "Codec name: " + codecName);
            if ("OMX.hisi.video.encoder.avc".equals(codecName)) {
                Log.w(TAG, "hisi h264 encoder does not set 'MediaFormat.KEY_PROFILE'.");
                //format.setInteger(MediaFormat.KEY_PROFILE, config.profile);
            }else{
                format.setInteger(MediaFormat.KEY_PROFILE, config.profile);
            }

            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            // Create input surface *before* starting the encoder
            surface = encoder.createInputSurface();
            Log.d(TAG, "Input surface created successfully: " + surface);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to configure encoder for config: " + config + ", error: " + e.getMessage());
            if (surface != null) {
                surface.release();
                surface = null;
            }
            if (encoder != null) {
                try {
                    encoder.release();
                } catch (Exception ignored) {
                }
                encoder = null;
            }
            return false;
        }
    }

    private boolean startEncoder() {
        try {
            encoder.start();
            encoderOutputBuffers = encoder.getOutputBuffers();
            Log.d(TAG, "Encoder started successfully");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to start encoder: " + e.getMessage());
            if (surface != null) {
                surface.release();
                surface = null;
            }
            if (encoder != null) {
                try {
                    encoder.release();
                } catch (Exception ignored) {
                }
                encoder = null;
            }
            return false;
        }
    }

    private List<EncoderConfig> getSupportedConfigurations(int frameWidth, int frameHeight) {
        
        int[] bitrates = {6000000, 4000000, 2000000, 1000000};
        int[] profiles = {
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        };
        List<int[]> resolutions = new ArrayList<>();
        resolutions.add(new int[]{frameWidth, frameHeight});
        for (int[] res : Arrays.asList(
                new int[]{1920, 1080},
                new int[]{1280, 720},
                new int[]{854, 480},
                new int[]{640, 360},
                new int[]{426, 240})) {
            // only add resolutions bellow the original stream resolution
            if (res[0] <= frameWidth && res[1] <= frameHeight) {
                resolutions.add(res);
            }
        }

        List<EncoderConfig> configs = new ArrayList<>();
        for (int[] res : resolutions) {
            for (int bitrate : bitrates) {
                for (int profile : profiles) {
                    configs.add(new EncoderConfig(res[0], res[1], bitrate, profile));
                }
            }
        }

        // Sort: prioritize higher resolutions, higher bitrates, Baseline profile
        Collections.sort(configs, new Comparator<EncoderConfig>() {
            @Override
            public int compare(EncoderConfig c1, EncoderConfig c2) {
                int resCompare = Integer.compare(c2.width * c2.height, c1.width * c1.height);
                if (resCompare != 0) return resCompare;
                int bitrateCompare = Integer.compare(c2.bitrate, c1.bitrate);
                if (bitrateCompare != 0) return bitrateCompare;
                return Integer.compare(c1.profile, c2.profile); // Baseline first
            }
        });

        return configs;
    }

    private boolean isProfileSupported(MediaCodecInfo codecInfo, String mimeType, int profile) {
        try {
            MediaCodecInfo.CodecCapabilities caps = codecInfo.getCapabilitiesForType(mimeType);
            for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
                if (pl.profile == profile) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to check profile support: " + e.getMessage());
        }
        return false;
    }


    private void initVideoEncoder(int frameWidth, int frameHeight) {
        if (encoder != null) {
            encoder.stop();
            encoder.release();
            encoder = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }

        // Select the encoder that supports COLOR_FormatSurface.
        // OMX.qcom.video.encoder.avc (Qualcomm HW) rejects COLOR_FormatSurface;
        // we must find a codec that explicitly supports it before iterating configs.
        if (preferredEncoderName == null) {
            preferredEncoderName = findEncoderSupportingSurface();
        }

        // Check codec capabilities using the preferred encoder (or the default one).
        MediaCodecInfo codecInfo = null;
        try {
            MediaCodec probe;
            if (preferredEncoderName != null) {
                probe = MediaCodec.createByCodecName(preferredEncoderName);
            } else {
                probe = MediaCodec.createEncoderByType(MIME_TYPE);
            }
            codecInfo = probe.getCodecInfo();
            probe.release();
        } catch (Exception e) {
            Log.e(TAG, "Failed to get codec info: " + e.getMessage());
        }

        List<EncoderConfig> configs = getSupportedConfigurations(frameWidth, frameHeight);

        for (EncoderConfig config : configs) {
            // Skip unsupported configurations
            if (codecInfo != null) {
                MediaCodecInfo.VideoCapabilities videoCaps = codecInfo.getCapabilitiesForType(MIME_TYPE).getVideoCapabilities();
                if (!videoCaps.isSizeSupported(config.width, config.height)) {
                    Log.d(TAG, "Skipping unsupported resolution: " + config);
                    continue;
                }
                if (!videoCaps.getBitrateRange().contains(config.bitrate)) {
                    Log.d(TAG, "Skipping unsupported bitrate: " + config);
                    continue;
                }
                if (!isProfileSupported(codecInfo, MIME_TYPE, config.profile)) {
                    Log.d(TAG, "Skipping unsupported profile: " + config);
                    continue;
                }
            }

            if (tryConfigureEncoder(config) && startEncoder()) {
                outputFileWidth = config.width;
                outputFileHeight = config.height;
                CountDownLatch latch = new CountDownLatch(1);
                renderThreadHandler.post(() -> {
                    try {
                        eglBase = EglBase.create(sharedContext, EglBase.CONFIG_RECORDABLE);
                        Log.d(TAG, "EGL context created");
                        eglBase.createSurface(surface);
                        eglBase.makeCurrent();
                        drawer = new GlRectDrawer();
                        encoderStarted = true;
                        encoderInitializing = false;
                        Log.d(TAG, "Encoder surface setup complete: " + surface);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to setup EGL surface: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    // Do NOT re-interrupt the thread — it would cause all subsequent latch.await()
                    // calls to throw immediately, cascading failure across all remaining configs.
                    Log.e(TAG, "Interrupted while awaiting EGL setup: " + e.getMessage());
                }
                if (encoderStarted) {
                    return;
                }
            }
        }

        Log.e(TAG, "Failed to configure and start encoder with any supported configuration.");
        // Reset state so the next incoming frame can retry initialization.
        outputFileWidth = -1;
        encoderInitializing = false;
    }
    @Override
    public void onFrame(VideoFrame frame) {
        frame.retain();
        if (outputFileWidth == -1 && !encoderInitializing) {
            encoderInitializing = true;
            int frameWidth = frame.getRotatedWidth();
            int frameHeight = frame.getRotatedHeight();
            initVideoEncoder(frameWidth, frameHeight);
        }
        // Only post to the render thread once the encoder and EGL drawer are fully ready.
        // Posting before encoderStarted is true means drawer == null, causing dropped frames.
        if (encoderStarted) {
            renderThreadHandler.post(() -> renderFrameOnRenderThread(frame));
        } else {
            frame.release();
        }
    }

    private void renderFrameOnRenderThread(VideoFrame frame) {
        if (drawer == null) {
            Log.e(TAG, "drawer is null — skipping frame render");
            frame.release();
            return;
        }

        if (frameDrawer == null) {
            frameDrawer = new VideoFrameDrawer();
        }
        frameDrawer.drawFrame(frame, drawer, null, 0, 0, outputFileWidth, outputFileHeight);
        frame.release();
        drainEncoder();
        eglBase.swapBuffers();
    }

    /**
     * Release all resources. All already posted frames will be rendered first.
     */
    // Start Signify modification
    void release() {
        isRunning = false;
        CountDownLatch latch = new CountDownLatch(audioThreadHandler  != null ? 2 : 1);
        if (audioThreadHandler != null) {
            audioThreadHandler.post(() -> {
                try{
                    if (audioEncoder != null) {
                        audioEncoder.stop();
                        audioEncoder.release();
                    }
                    audioThread.quit();
                } finally {
                    latch.countDown();
                }
            });
        }

        renderThreadHandler.post(() -> {
            try {
                if (encoder != null) {
                    encoder.stop();
                    encoder.release();
                }
                if (eglBase != null) {
                    eglBase.release();
                    eglBase = null;
                }
                if (muxerStarted) {
                    mediaMuxer.stop();
                    mediaMuxer.release();
                    muxerStarted = false;
                }
                renderThread.quit();
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Log.e(TAG, "Release interrupted", e);
            Thread.currentThread().interrupt();
        }
    }
    // End Signify modification
    private boolean encoderInitializing = false;
    private boolean encoderStarted = false;
    private volatile boolean muxerStarted = false;
    private long videoFrameStart = 0;

    private void drainEncoder() {
        while (true) {
            int encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, 10000);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = encoder.getOutputBuffers();
                Log.e(TAG, "encoder output buffers changed");
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // not expected for an encoder
                MediaFormat newFormat = encoder.getOutputFormat();

                Log.e(TAG, "encoder output format changed: " + newFormat);
                synchronized (mediaMuxer) {
                    trackIndex = mediaMuxer.addTrack(newFormat);
                }
                // Start Signify modification
                
                synchronized (mediaMuxer) {
                    if (trackIndex != -1 && audioTrackIndex != -1 && !muxerStarted) {
                // End Signify modification

                        mediaMuxer.start();
                        muxerStarted = true;
                    }
                }
                if (!muxerStarted)
                    break;
            } else if (encoderStatus < 0) {
                Log.e(TAG, "unexpected result fr om encoder.dequeueOutputBuffer: " + encoderStatus);
            } else { // encoderStatus >= 0
                try {
                    ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                    if (encodedData == null) {
                        Log.e(TAG, "encoderOutputBuffer " + encoderStatus + " was null");
                        break;
                    }
                    // It's usually necessary to adjust the ByteBuffer values to match BufferInfo.
                    encodedData.position(bufferInfo.offset);
                    encodedData.limit(bufferInfo.offset + bufferInfo.size);
                    if (videoFrameStart == 0 && bufferInfo.presentationTimeUs != 0) {
                        videoFrameStart = bufferInfo.presentationTimeUs;
                    }
                    bufferInfo.presentationTimeUs -= videoFrameStart;
                    if (muxerStarted)
                        synchronized (mediaMuxer) { mediaMuxer.writeSampleData(trackIndex, encodedData, bufferInfo); }
                    isRunning = isRunning && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
                    encoder.releaseOutputBuffer(encoderStatus, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                } catch (Exception e) {
                    Log.wtf(TAG, e);
                    break;
                }
            }
        }
    }

    private long presTime = 0L;



    private void drainAudio() {
        if (audioBufferInfo == null)
            audioBufferInfo = new MediaCodec.BufferInfo();

        while (true) {
            int encoderStatus = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 1000);

            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                audioOutputBuffers = audioEncoder.getOutputBuffers();
                Log.w(TAG, "encoder output buffers changed");
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // not expected for an encoder
                MediaFormat newFormat = audioEncoder.getOutputFormat();

                Log.w(TAG, "encoder output format changed: " + newFormat);
                synchronized (mediaMuxer) {
                    audioTrackIndex = mediaMuxer.addTrack(newFormat);
                }
                // Start Signify modification
                synchronized (mediaMuxer) {
                    if (trackIndex != -1 && audioTrackIndex != -1 && !muxerStarted) {
                // End Signify modification
                        mediaMuxer.start();
                        muxerStarted = true;
                    }
                }
                if (!muxerStarted)
                    break;
            } else if (encoderStatus < 0) {
                Log.e(TAG, "unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
            } else { // encoderStatus >= 0

                try {
                    ByteBuffer encodedData = audioOutputBuffers[encoderStatus];
                    if (encodedData == null) {
                        Log.e(TAG, "encoderOutputBuffer " + encoderStatus + " was null");
                        break;
                    }

                    // It's usually necessary to adjust the ByteBuffer values to match BufferInfo.
                    encodedData.position(audioBufferInfo.offset);
                    encodedData.limit(audioBufferInfo.offset + audioBufferInfo.size);

                    if (muxerStarted)
                        synchronized (mediaMuxer) { mediaMuxer.writeSampleData(audioTrackIndex, encodedData, audioBufferInfo); }

                    isRunning = isRunning && (audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
                    audioEncoder.releaseOutputBuffer(encoderStatus, false);

                    if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }

                } catch (Exception e) {
                    Log.wtf(TAG, e);
                    break;
                }
            }
        }
    }

    @Override
    public void onWebRtcAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples audioSamples) {
        if (!isRunning)
            return;
        audioThreadHandler.post(() -> {
            if (audioEncoder == null) try {
                audioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
                MediaFormat format = new MediaFormat();
                format.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");
                format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, audioSamples.getChannelCount());
                format.setInteger(MediaFormat.KEY_SAMPLE_RATE, audioSamples.getSampleRate());
                format.setInteger(MediaFormat.KEY_BIT_RATE, 64 * 1024);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                audioEncoder.start();
                audioInputBuffers = audioEncoder.getInputBuffers();
                audioOutputBuffers = audioEncoder.getOutputBuffers();
            } catch (IOException exception) {
                Log.wtf(TAG, exception);
            }

            int bufferIndex = audioEncoder.dequeueInputBuffer(0);
            if (bufferIndex >= 0) {
                ByteBuffer buffer = audioInputBuffers[bufferIndex];
                buffer.clear();
                byte[] data = audioSamples.getData();
                buffer.put(data);
                audioEncoder.queueInputBuffer(bufferIndex, 0, data.length, presTime, 0);
                presTime += data.length * 125 / 12; // 1000000 microseconds / 48000hz / 2 bytes
            }
            drainAudio();
        });
    }

}