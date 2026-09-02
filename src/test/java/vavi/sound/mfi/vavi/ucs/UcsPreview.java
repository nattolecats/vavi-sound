/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 */

package vavi.sound.mfi.vavi.ucs;

import java.io.File;
import java.util.HexFormat;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

import vavi.sound.mfi.MfiEvent;
import vavi.sound.mfi.MfiSystem;
import vavi.sound.mfi.Sequence;
import vavi.sound.mfi.Track;
import vavi.sound.mfi.vavi.track.MachineDependentMessage;


/** Auditions one decoded UCS waveform without the MIDI or ADPCM tracks. */
public final class UcsPreview {

    private UcsPreview() {
    }

    /**
     * @param args MLD file and UCS waveform number (0 through 7)
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: UcsPreview <file.mld> <wave-number>");
        }

        loadUcsPackets(new File(args[0]));
        int number = Integer.parseInt(args[1]);
        UcsSequencer.Wave wave = UcsSequencer.waveBank().wave(number);
        if (!wave.enabled || wave.data == null || wave.data.length == 0) {
            throw new IllegalArgumentException("UCS wave is not present: " + number);
        }
        System.out.printf("wave=%d bytes=%d declared=%d loop=%d..%d rate=%d rootPitch=%.3f params=%s%n",
                          number, wave.data.length, wave.length, wave.loopStart, wave.loopEnd,
                          wave.sampleRate, wave.rootPitch,
                          wave.parameters == null ? "" : HexFormat.of().formatHex(wave.parameters));

        AudioFormat format = new AudioFormat(wave.sampleRate, 16, 1, true, false);
        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format);
            line.start();
            line.write(toPcm16(wave, wave.sampleRate * 5), 0, wave.sampleRate * 5 * 2);
            line.drain();
        }
    }

    private static void loadUcsPackets(File file) throws Exception {
        UcsSequencer sequencer = new UcsSequencer();
        Sequence sequence = MfiSystem.getSequence(file);
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MfiEvent event = track.get(i);
                if (event.getMessage() instanceof MachineDependentMessage message &&
                    (message.getVendor() | message.getCarrier()) == UcsSequencer.ID) {
                    sequencer.sequence(message);
                }
            }
        }
    }

    private static byte[] toPcm16(UcsSequencer.Wave wave, int samples) {
        byte[] pcm = new byte[samples * 2];
        int position = 0;
        int loopStart = Math.min(wave.loopStart, wave.data.length - 1);
        int loopEnd = Math.min(Math.max(wave.loopEnd, loopStart + 1), wave.data.length);
        for (int i = 0; i < samples; i++) {
            int sample = wave.data[position++] << 8;
            pcm[i * 2] = (byte) sample;
            pcm[i * 2 + 1] = (byte) (sample >>> 8);
            if (position >= wave.data.length) {
                position = loopStart < loopEnd ? loopStart : 0;
            }
        }
        return pcm;
    }
}
