/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 */

package vavi.sound.mfi.vavi.ucs;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Arrays;

import vavi.sound.mfi.InvalidMfiDataException;
import vavi.sound.mfi.vavi.sequencer.MachineDependentSequencer;
import vavi.sound.mfi.vavi.track.MachineDependentMessage;

import static java.lang.System.getLogger;


/**
 * DoCoMo UCS (Universal Characteristic Sound) message sequencer.
 *
 * <p>The vendor nibble is zero for UCS.  Consequently its complete vendor/carrier
 * identifier is {@code 0x01}, which must not be treated as an unknown vendor.</p>
 */
public final class UcsSequencer implements MachineDependentSequencer {

    private static final Logger logger = getLogger(UcsSequencer.class.getName());

    /** vendor 0 + DoCoMo carrier 1 */
    public static final int ID = 0x01;

    private static final UcsWaveBank waveBank = new UcsWaveBank();

    @Override
    public int getId() {
        return ID;
    }

    @Override
    public void sequence(MachineDependentMessage message) throws InvalidMfiDataException {
        byte[] data = message.getMessage();
        if (data.length < 7) {
            logger.log(Level.WARNING, "truncated UCS message");
            return;
        }

        switch (data[6] & 0xff) {
        case 0x10 -> waveBank.setWave(data);
        case 0x11 -> waveBank.setParameters(data);
        case 0x12 -> waveBank.setAdminStatus(data);
        case 0x40 -> waveBank.setPart(data);
        default -> logger.log(Level.DEBUG, "unsupported UCS function: 0x%02x".formatted(data[6] & 0xff));
        }
    }

    /** Package-visible for focused decoding tests. */
    static UcsWaveBank waveBank() {
        return waveBank;
    }

    /** Stateful UCS wave packets, shared by the ServiceLoader-created sequencer. */
    static final class UcsWaveBank {
        private static final int HEADER = 7;
        private final Wave[] waves = new Wave[256];
        private final Part[] parts = new Part[256];

        /**
         * Records an UCS part definition.  In the target file these are the
         * three DoCoMo UCS tone IDs {@code 0x81}, {@code 0x82}, and
         * {@code 0x83}; ordinary MFi program changes alone do not identify
         * these custom PCM tones.
         */
        void setPart(byte[] data) {
            if (data.length < HEADER + 1) {
                logger.log(Level.WARNING, "truncated UCS part message");
                return;
            }
            int number = data[7] & 0xff;
            Part part = parts[number];
            if (part == null) {
                part = new Part();
                parts[number] = part;
            }
            part.number = number;
            part.data = Arrays.copyOfRange(data, HEADER, data.length);
        }

        void setWave(byte[] data) {
            if (data.length < HEADER + 2) {
                logger.log(Level.WARNING, "truncated UCS wave message");
                return;
            }
            int number = data[7] & 0xff;
            int type = data[8] & 0xff;
            Wave wave = waves[number];
            if (wave == null) {
                wave = new Wave();
                waves[number] = wave;
            }

            if (type == 1) {
                if (data.length < HEADER + 9) {
                    logger.log(Level.WARNING, "truncated UCS wave definition: " + number);
                    return;
                }
                wave.length = unsigned24(data, 9);
                wave.loopStart = unsigned24(data, 12);
                wave.loopEnd = unsigned24(data, 15);
                wave.data = null;
            } else if (type == 2) {
                if (data.length < HEADER + 4) {
                    logger.log(Level.WARNING, "truncated UCS wave data: " + number);
                    return;
                }
                int length = unsigned16(data, 9);
                int available = data.length - 11;
                if (length > available) {
                    logger.log(Level.WARNING, "truncated UCS wave data: " + number + ", declared=" + length + ", available=" + available);
                    length = available;
                }
                byte[] packet = Arrays.copyOfRange(data, 11, 11 + length);
                wave.data = wave.data == null ? packet : append(wave.data, packet);
                if (wave.length != 0 && wave.data.length > wave.length) {
                    wave.data = Arrays.copyOf(wave.data, wave.length);
                }
            } else {
                logger.log(Level.WARNING, "unsupported UCS wave packet type: " + type);
            }
        }

        void setParameters(byte[] data) {
            if (data.length < HEADER + 3) {
                logger.log(Level.WARNING, "truncated UCS wave parameters");
                return;
            }
            int length = data[9] & 0xff;
            int available = data.length - (HEADER + 3);
            if (length > available) {
                logger.log(Level.WARNING, "truncated UCS wave parameters: declared=" + length + ", available=" + available);
                length = available;
            }
            Wave wave = wave(data[7] & 0xff);
            wave.parameters = Arrays.copyOfRange(data, HEADER + 3, HEADER + 3 + length);
            if (length >= 8) {
                // The fixed 0x2c immediately before this block is its byte
                // length, not a MIDI key.  The tuning is an 8.8 fixed-point
                // MIDI note at parameter offsets 6 and 7.
                wave.rootPitch = unsigned16(wave.parameters, 6) / 256d;
            }
        }

        void setAdminStatus(byte[] data) {
            if (data.length >= HEADER + 1) {
                wave(data[7] & 0xff).enabled = true;
            }
        }

        Wave wave(int number) {
            Wave wave = waves[number];
            if (wave == null) {
                wave = new Wave();
                waves[number] = wave;
            }
            return wave;
        }

        Part part(int number) {
            return parts[number];
        }

        private static int unsigned16(byte[] data, int offset) {
            return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
        }

        private static int unsigned24(byte[] data, int offset) {
            return ((data[offset] & 0xff) << 16) | ((data[offset + 1] & 0xff) << 8) | (data[offset + 2] & 0xff);
        }

        private static byte[] append(byte[] left, byte[] right) {
            byte[] result = Arrays.copyOf(left, left.length + right.length);
            System.arraycopy(right, 0, result, left.length, right.length);
            return result;
        }
    }

    static final class Wave {
        int length;
        int loopStart;
        int loopEnd;
        /** UCS 8.8 fixed-point MIDI reference pitch. */
        double rootPitch = 60;
        /** UCS wave parameter packet, retained for envelope/filter decoding. */
        byte[] parameters;
        /** FueTrek's UCS-capable software synthesizer uses a 24 kHz wave table. */
        int sampleRate = 24_000;
        boolean enabled;
        byte[] data;
    }

    /** Raw UCS part-definition packet retained until its waveform mapping is resolved. */
    static final class Part {
        int number;
        byte[] data;
    }
}
