/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 */

package vavi.sound.mfi.vavi.ucs;

import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;


/** Small polyphonic renderer for the signed-PCM waves embedded in DoCoMo UCS. */
public final class UcsAudioEngine implements AutoCloseable {

    private static final int SAMPLE_RATE = 24_000;
    private static final int FRAMES = 256;

    /** The three custom-tone slots emitted by the MFi 5 DoCoMo writer. */
    private static final int[] PARTS = { 0x81, 0x82, 0x83 };
    /** The MFi pseudo-MIDI voices selected by those slots. */
    private static final int[] CHANNELS = { 2, 6, 9 };

    private final List<Voice> voices = new ArrayList<>();
    private final Object lock = new Object();
    private volatile boolean running;
    private SourceDataLine line;

    /** Returns whether {@code channel} is occupied by a loaded UCS custom tone. */
    public boolean isUcsChannel(int channel) {
        for (int i = 0; i < CHANNELS.length; i++) {
            if (CHANNELS[i] == channel && UcsSequencer.waveBank().part(PARTS[i]) != null) {
                return true;
            }
        }
        return false;
    }

    /** Starts a sampled UCS note. */
    public void noteOn(int channel, int note, int velocity) {
        int part = partFor(channel);
        if (part < 0 || velocity == 0) {
            noteOff(channel, note);
            return;
        }

        UcsSequencer.Wave wave = waveFor(note);
        if (wave == null || !wave.enabled || wave.data == null || wave.data.length == 0) {
            return;
        }

        ensureStarted();
        double increment = Math.pow(2d, (note - wave.rootPitch) / 12d) * wave.sampleRate / SAMPLE_RATE;
        synchronized (lock) {
            noteOffLocked(channel, note);
            voices.add(new Voice(channel, note, wave, increment, velocity / 127d));
        }
    }

    /** Stops a sampled UCS note. */
    public void noteOff(int channel, int note) {
        synchronized (lock) {
            noteOffLocked(channel, note);
        }
    }

    private void noteOffLocked(int channel, int note) {
        for (Voice voice : voices) {
            if (voice.channel == channel && voice.note == note) {
                voice.releasing = true;
            }
        }
    }

    private int partFor(int channel) {
        for (int i = 0; i < CHANNELS.length; i++) {
            if (CHANNELS[i] == channel && UcsSequencer.waveBank().part(PARTS[i]) != null) {
                return PARTS[i];
            }
        }
        return -1;
    }

    /** Chooses the closest UCS multisample reference pitch for a played note. */
    private static UcsSequencer.Wave waveFor(int note) {
        UcsSequencer.Wave selected = null;
        double difference = Double.MAX_VALUE;
        for (int number = 0; number < 256; number++) {
            UcsSequencer.Wave candidate = UcsSequencer.waveBank().wave(number);
            if (!candidate.enabled || candidate.data == null || candidate.data.length == 0) continue;
            double candidateDifference = Math.abs(note - candidate.rootPitch);
            if (candidateDifference < difference) {
                selected = candidate;
                difference = candidateDifference;
            }
        }
        return selected;
    }

    private synchronized void ensureStarted() {
        if (running) return;
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, FRAMES * 8);
            line.start();
            running = true;
            Thread renderer = new Thread(this::render, "UCS PCM renderer");
            renderer.setDaemon(true);
            renderer.start();
        } catch (LineUnavailableException e) {
            throw new IllegalStateException("cannot open UCS PCM output", e);
        }
    }

    private void render() {
        byte[] pcm = new byte[FRAMES * 2];
        while (running) {
            synchronized (lock) {
                for (int i = 0; i < FRAMES; i++) {
                    int mixed = 0;
                    for (int v = voices.size() - 1; v >= 0; v--) {
                        Voice voice = voices.get(v);
                        mixed += voice.sample();
                        if (voice.finished()) voices.remove(v);
                    }
                    mixed = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mixed));
                    pcm[i * 2] = (byte) mixed;
                    pcm[i * 2 + 1] = (byte) (mixed >>> 8);
                }
            }
            line.write(pcm, 0, pcm.length);
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        synchronized (lock) {
            voices.clear();
        }
        if (line != null) {
            line.stop();
            line.close();
            line = null;
        }
    }

    private static final class Voice {
        final int channel;
        final int note;
        final UcsSequencer.Wave wave;
        final double increment;
        final double level;
        double position;
        double release = 1;
        boolean releasing;

        Voice(int channel, int note, UcsSequencer.Wave wave, double increment, double level) {
            this.channel = channel;
            this.note = note;
            this.wave = wave;
            this.increment = increment;
            this.level = level;
        }

        int sample() {
            int index = Math.min((int) position, wave.data.length - 1);
            int result = (int) ((wave.data[index] << 8) * level * release);
            position += increment;
            int loopStart = Math.min(wave.loopStart, wave.data.length - 1);
            int loopEnd = Math.min(Math.max(wave.loopEnd, loopStart + 1), wave.data.length);
            if (position >= loopEnd) position = loopStart + (position - loopEnd);
            if (releasing) release *= 0.995;
            return result;
        }

        boolean finished() {
            return releasing && release < 0.001;
        }
    }
}
