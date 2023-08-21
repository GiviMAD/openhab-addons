/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.voice.rustpotterks.internal;

import static org.openhab.voice.rustpotterks.internal.RustpotterKSConstants.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.OpenHAB;
import org.openhab.core.audio.AudioFormat;
import org.openhab.core.audio.AudioStream;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.config.core.ConfigurableService;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.voice.KSErrorEvent;
import org.openhab.core.voice.KSException;
import org.openhab.core.voice.KSListener;
import org.openhab.core.voice.KSService;
import org.openhab.core.voice.KSServiceHandle;
import org.openhab.core.voice.KSpottedEvent;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.givimad.rustpotter_java.Endianness;
import io.github.givimad.rustpotter_java.Rustpotter;
import io.github.givimad.rustpotter_java.RustpotterBuilder;
import io.github.givimad.rustpotter_java.SampleFormat;
import io.github.givimad.rustpotter_java.ScoreMode;

/**
 * The {@link RustpotterKSService} is a keyword spotting implementation based on rustpotter.
 *
 * @author Miguel Álvarez - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = SERVICE_PID, property = Constants.SERVICE_PID + "=" + SERVICE_PID)
@ConfigurableService(category = SERVICE_CATEGORY, label = SERVICE_NAME
        + " Keyword Spotter", description_uri = SERVICE_CATEGORY + ":" + SERVICE_ID)
public class RustpotterKSService implements KSService {
    private static final String RUSTPOTTER_FOLDER = Path.of(OpenHAB.getUserDataFolder(), "rustpotter").toString();
    private static final String RUSTPOTTER_RECORDS_FOLDER = Path.of(RUSTPOTTER_FOLDER, "records").toString();
    private final Logger logger = LoggerFactory.getLogger(RustpotterKSService.class);
    private final ScheduledExecutorService executor = ThreadPoolManager.getScheduledPool("OH-voice-rustpotterks");
    private RustpotterKSConfiguration config = new RustpotterKSConfiguration();
    static {
        Logger logger = LoggerFactory.getLogger(RustpotterKSService.class);
        tryCreateDir(RUSTPOTTER_FOLDER, logger, "rustpotter dir created {}");
        tryCreateDir(RUSTPOTTER_RECORDS_FOLDER, logger, "rustpotter record dir created {}");
    }

    private static void tryCreateDir(String rustpotterFolder, Logger logger, String msg) {
        File addonDir = new File(rustpotterFolder);
        if (!addonDir.exists()) {
            if (addonDir.mkdir()) {
                logger.info(msg, rustpotterFolder);
            }
        }
    }

    @Activate
    protected void activate(Map<String, Object> config) {
        logger.debug("Loading library");
        try {
            Rustpotter.loadLibrary();
        } catch (IOException e) {
            logger.warn("Unable to load rustpotter native library: {}", e.getMessage());
        }
        modified(config);
    }

    @Modified
    protected void modified(Map<String, Object> config) {
        this.config = new Configuration(config).as(RustpotterKSConfiguration.class);
    }

    @Override
    public String getId() {
        return SERVICE_ID;
    }

    @Override
    public String getLabel(@Nullable Locale locale) {
        return SERVICE_NAME;
    }

    @Override
    public Set<Locale> getSupportedLocales() {
        return Set.of();
    }

    @Override
    public Set<AudioFormat> getSupportedFormats() {
        return Set.of(
                new AudioFormat(AudioFormat.CONTAINER_WAVE, AudioFormat.CODEC_PCM_SIGNED, false, 16, null, 16000L),
                new AudioFormat(AudioFormat.CONTAINER_WAVE, AudioFormat.CODEC_PCM_SIGNED, null, 16, null, null),
                new AudioFormat(AudioFormat.CONTAINER_WAVE, AudioFormat.CODEC_PCM_SIGNED, null, 32, null, null),
                new AudioFormat(AudioFormat.CONTAINER_WAVE, AudioFormat.CODEC_PCM_SIGNED, null, 8, null, null));
    }

    @Override
    public KSServiceHandle spot(KSListener ksListener, AudioStream audioStream, Locale locale, String keyword)
            throws KSException {
        var audioFormat = audioStream.getFormat();
        var frequency = audioFormat.getFrequency();
        var bitDepth = audioFormat.getBitDepth();
        var channels = audioFormat.getChannels();
        var isBigEndian = audioFormat.isBigEndian();
        if (frequency == null || bitDepth == null || channels == null || isBigEndian == null) {
            throw new KSException(
                    "Missing stream metadata: frequency, bit depth, channels and endianness must be defined.");
        }
        var endianness = isBigEndian ? Endianness.BIG : Endianness.LITTLE;
        logger.debug("Audio wav spec: frequency '{}', bit depth '{}', channels '{}', '{}'", frequency, bitDepth,
                channels, isBigEndian ? "big-endian" : "little-endian");
        Rustpotter rustpotter;
        try {
            rustpotter = initRustpotter(frequency, bitDepth, channels, endianness);
        } catch (Exception e) {
            throw new KSException("Unable to configure rustpotter: " + e.getMessage(), e);
        }
        var modelName = keyword.replaceAll("\\s", "_") + ".rpw";
        var modelPath = Path.of(RUSTPOTTER_FOLDER, modelName);
        if (!modelPath.toFile().exists()) {
            throw new KSException("Missing model: " + modelPath);
        }
        try {
            rustpotter.addWakewordModelFile(modelPath.toString());
        } catch (Exception e) {
            throw new KSException("Unable to load wake word model: " + e.getMessage());
        }
        logger.debug("Model '{}' loaded", modelPath);
        AtomicBoolean aborted = new AtomicBoolean(false);
        executor.submit(() -> processAudioStream(rustpotter, ksListener, audioStream, aborted));
        return () -> {
            logger.debug("Stopping service");
            aborted.set(true);
        };
    }

    private Rustpotter initRustpotter(long frequency, int bitDepth, int channels, Endianness endianness)
            throws Exception {
        var rustpotterBuilder = new RustpotterBuilder();
        // audio configs
        rustpotterBuilder.setSampleFormat(getIntSampleFormat(bitDepth));
        rustpotterBuilder.setSampleRate(frequency);
        rustpotterBuilder.setChannels(channels);
        rustpotterBuilder.setEndianness(endianness);
        // detector configs
        rustpotterBuilder.setThreshold(config.threshold);
        rustpotterBuilder.setAveragedThreshold(config.averagedThreshold);
        rustpotterBuilder.setScoreMode(getScoreMode(config.scoreMode));
        rustpotterBuilder.setMinScores(config.minScores);
        rustpotterBuilder.setScoreRef(config.scoreRef);
        rustpotterBuilder.setBandSize(config.bandSize);
        rustpotterBuilder.setRecordPath(config.record ? RUSTPOTTER_RECORDS_FOLDER : null);
        // filter configs
        rustpotterBuilder.setGainNormalizerEnabled(config.gainNormalizer);
        rustpotterBuilder.setMinGain(config.minGain);
        rustpotterBuilder.setMaxGain(config.maxGain);
        rustpotterBuilder.setGainRef(config.gainRef);
        rustpotterBuilder.setBandPassFilterEnabled(config.bandPass);
        rustpotterBuilder.setBandPassLowCutoff(config.lowCutoff);
        rustpotterBuilder.setBandPassHighCutoff(config.highCutoff);
        // init the detector
        var rustpotter = rustpotterBuilder.build();
        rustpotterBuilder.delete();
        return rustpotter;
    }

    private void processAudioStream(Rustpotter rustpotter, KSListener ksListener, AudioStream audioStream,
            AtomicBoolean aborted) {
        int numBytesRead;
        var bufferSize = (int) rustpotter.getBytesPerFrame();
        byte[] audioBuffer = new byte[bufferSize];
        int remaining = bufferSize;
        while (!aborted.get()) {
            try {
                numBytesRead = audioStream.read(audioBuffer, bufferSize - remaining, remaining);
                if (aborted.get() || numBytesRead == -1) {
                    break;
                }
                if (numBytesRead != remaining) {
                    remaining = remaining - numBytesRead;
                    continue;
                }
                remaining = bufferSize;
                var result = rustpotter.processBytes(audioBuffer);
                if (result.isPresent()) {
                    var detection = result.get();
                    if (logger.isDebugEnabled()) {
                        ArrayList<String> scores = new ArrayList<>();
                        var scoreNames = detection.getScoreNames().split("\\|\\|");
                        var scoreValues = detection.getScores();
                        for (var i = 0; i < Integer.min(scoreNames.length, scoreValues.length); i++) {
                            scores.add("'" + scoreNames[i] + "': " + scoreValues[i]);
                        }
                        logger.debug("Detected '{}' with: Score: {}, AvgScore: {}, Count: {}, Gain: {}, Scores: {}",
                                detection.getName(), detection.getScore(), detection.getAvgScore(),
                                detection.getCounter(), detection.getGain(), String.join(", ", scores));
                    }
                    detection.delete();
                    ksListener.ksEventReceived(new KSpottedEvent());
                }
            } catch (IOException e) {
                String errorMessage = e.getMessage();
                ksListener.ksEventReceived(new KSErrorEvent(errorMessage != null ? errorMessage : "Unexpected error"));
            }
        }
        rustpotter.delete();
        logger.debug("rustpotter stopped");
    }

    private static SampleFormat getIntSampleFormat(int bitDepth) throws IOException {
        return switch (bitDepth) {
            case 8 -> SampleFormat.I8;
            case 16 -> SampleFormat.I16;
            case 32 -> SampleFormat.I32;
            default -> throw new IOException("Unsupported audio bit depth: " + bitDepth);
        };
    }

    private ScoreMode getScoreMode(String mode) {
        return switch (mode) {
            case "average" -> ScoreMode.AVG;
            case "median" -> ScoreMode.MEDIAN;
            case "p25" -> ScoreMode.P25;
            case "p50" -> ScoreMode.P50;
            case "p75" -> ScoreMode.P75;
            case "p80" -> ScoreMode.P80;
            case "p90" -> ScoreMode.P90;
            case "p95" -> ScoreMode.P95;
            default -> ScoreMode.MAX;
        };
    }
}
