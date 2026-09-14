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
 *
 * {@link #loop} instead starts a cue that repeats until {@link #stopLoop} is
 * called, for alerts that should keep sounding for as long as the mechanic they
 * warn about is up. Only one loop per resource path runs at a time.
 */
@Slf4j
public final class SoundPlayer {
    private static final Map<String, CachedSound> CACHE = new HashMap<>();
    // Sounds this JVM already failed to load, so a missing/unsupported file logs
    // once instead of on every trigger.
    private static final Map<String, Boolean> FAILED = new HashMap<>();
    // Currently looping clips, keyed by resource path, so a loop can be stopped
    // again without the caller having to hold on to the Clip itself.
    private static final Map<String, Clip> LOOPS = new HashMap<>();
    // Silence appended to a looping cue so its repeats are spaced apart instead of
    // butting up against each other. Short enough to still read as a steady alert.
    private static final int LOOP_GAP_MS = 250;

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

    /**
     * Starts {@code resourcePath} looping until {@link #stopLoop} is called. If a
     * loop for the same path is already running it is left playing and only its
     * volume is refreshed, so this is safe to call every tick for as long as the
     * alert should be sounding. Repeats are separated by {@link #LOOP_GAP_MS} of
     * silence.
     *
     * @param volumePercent 0-100; 0 stops any running loop and starts nothing
     */
    public static synchronized void loop(String resourcePath, int volumePercent) {
        if (volumePercent <= 0) {
            stopLoop(resourcePath);
            return;
        }

        Clip running = LOOPS.get(resourcePath);
        if (running != null && running.isOpen()) {
            applyVolume(running, volumePercent);
            return;
        }

        CachedSound sound = load(resourcePath);
        if (sound == null) {
            return;
        }

        try {
            // Loop the cue with a little silence appended rather than the bare file,
            // so repeats are spaced out instead of running straight into each other.
            byte[] pcm = withTrailingSilence(sound);
            Clip clip = (Clip) AudioSystem.getLine(new DataLine.Info(Clip.class, sound.format));
            clip.open(sound.format, pcm, 0, pcm.length);
            applyVolume(clip, volumePercent);
            clip.loop(Clip.LOOP_CONTINUOUSLY);
            LOOPS.put(resourcePath, clip);
        } catch (Exception e) {
            log.warn("Failed to loop sound {}", resourcePath, e);
        }
    }

    // The cue's PCM followed by LOOP_GAP_MS of silence, which becomes the gap
    // between repeats once the clip loops. Returns the PCM unchanged if the format
    // does not report a frame rate to measure the gap against.
    private static byte[] withTrailingSilence(CachedSound sound) {
        AudioFormat format = sound.format;
        float frameRate = format.getFrameRate();
        int frameSize = format.getFrameSize();
        if (frameRate <= 0 || frameSize <= 0) {
            return sound.pcm;
        }

        int silenceBytes = Math.round(frameRate * LOOP_GAP_MS / 1000f) * frameSize;
        byte[] padded = new byte[sound.pcm.length + silenceBytes];
        System.arraycopy(sound.pcm, 0, padded, 0, sound.pcm.length);
        // 8-bit PCM is unsigned, where silence is mid-scale rather than zero; every
        // other sample size here is signed, for which the zero-filled array is right.
        if (format.getEncoding() == AudioFormat.Encoding.PCM_UNSIGNED
                && format.getSampleSizeInBits() == 8) {
            java.util.Arrays.fill(padded, sound.pcm.length, padded.length, (byte) 0x80);
        }
        return padded;
    }

    /** Stops and releases the loop started for {@code resourcePath}, if any. */
    public static synchronized void stopLoop(String resourcePath) {
        Clip clip = LOOPS.remove(resourcePath);
        if (clip == null) {
            return;
        }
        try {
            clip.stop();
            clip.close();
        } catch (Exception e) {
            log.warn("Failed to stop looping sound {}", resourcePath, e);
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
