package com.pvmkits.core;

import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Plays short bundled WAV cues through the OS mixer (javax.sound), independently
 * of the game's own audio. Files live under src/main/resources and are addressed
 * by classpath path, e.g. "/com/pvmkits/sounds/melee_punish.wav".
 *
 * Each file is decoded once into a raw PCM byte[] and cached; every play opens a
 * fresh Clip from that buffer and closes it when it finishes, so overlapping cues
 * do not cut each other off and no mixer line is left open.
 */
@Slf4j
public final class SoundPlayer {
    private static final Map<String, CachedSound> CACHE = new HashMap<>();
    // Sounds this JVM already failed to load, so a missing/unsupported file logs
    // once instead of on every trigger.
    private static final Map<String, Boolean> FAILED = new HashMap<>();

    private SoundPlayer() {
    }

    /**
     * @param resourcePath classpath path of a WAV, e.g. "/com/pvmkits/sounds/x.wav"
     * @param volumePercent 0-100; 0 is silent and skips playback entirely
     */
    public static void play(String resourcePath, int volumePercent) {
        if (volumePercent <= 0) {
            return;
        }

        CachedSound sound = load(resourcePath);
        if (sound == null) {
            return;
        }

        try {
            Clip clip = (Clip) AudioSystem.getLine(new DataLine.Info(Clip.class, sound.format));
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    clip.close();
                }
            });
            clip.open(sound.format, sound.pcm, 0, sound.pcm.length);
            applyVolume(clip, volumePercent);
            clip.start();
        } catch (Exception e) {
            log.warn("Failed to play sound {}", resourcePath, e);
        }
    }

    private static synchronized CachedSound load(String resourcePath) {
        CachedSound cached = CACHE.get(resourcePath);
        if (cached != null || FAILED.containsKey(resourcePath)) {
            return cached;
        }

        try (InputStream raw = SoundPlayer.class.getResourceAsStream(resourcePath)) {
            if (raw == null) {
                throw new IllegalStateException("resource not found on classpath");
            }
            // Buffer the stream: AudioSystem needs mark/reset to sniff the header.
            try (AudioInputStream in = AudioSystem.getAudioInputStream(
                    new ByteArrayInputStream(readAll(raw)))) {
                byte[] pcm = readAll(in);
                cached = new CachedSound(in.getFormat(), pcm);
                CACHE.put(resourcePath, cached);
                return cached;
            }
        } catch (Exception e) {
            FAILED.put(resourcePath, Boolean.TRUE);
            log.warn("Failed to load sound {}", resourcePath, e);
            return null;
        }
    }

    // Map 0-100 onto the line's dB gain range. The curve is quadratic so the slider
    // tracks perceived loudness rather than raw amplitude.
    private static void applyVolume(Clip clip, int volumePercent) {
        if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }
        FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
        double scale = Math.min(100, volumePercent) / 100.0;
        float db = (float) (20.0 * Math.log10(Math.max(scale * scale, 0.0001)));
        gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int read;
        while ((read = in.read(buf)) > 0) {
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    private static final class CachedSound {
        private final AudioFormat format;
        private final byte[] pcm;

        private CachedSound(AudioFormat format, byte[] pcm) {
            this.format = format;
            this.pcm = pcm;
        }
    }
}
