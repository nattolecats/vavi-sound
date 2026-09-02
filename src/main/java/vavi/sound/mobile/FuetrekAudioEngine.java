/*
 * Copyright (c) 2003 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mobile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteOrder;
import java.util.Locale;

import vavi.sound.adpcm.ccitt.G721InputStream;
import vavi.sound.adpcm.ccitt.G721OutputStream;
import vavi.sound.adpcm.ccitt.G723_16InputStream;
import vavi.sound.adpcm.dvi.DviInputStream;
import vavi.sound.adpcm.dvi.Ima2InputStream;
import vavi.sound.adpcm.ma.MaInputStream;
import vavi.sound.adpcm.oki.OkiInputStream;
import vavi.sound.adpcm.rohm.RohmInputStream;
import vavi.sound.adpcm.vox.VoxInputStream;
import vavi.sound.adpcm.yamaha.YamahaInputStream;

import static java.lang.System.getLogger;


/**
 * Fuetrek AudioEngine.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 020903 nsano initial version <br>
 */
public class FuetrekAudioEngine extends BasicAudioEngine {

    private static final Logger logger = getLogger(FuetrekAudioEngine.class.getName());

    /** */
    private static final int MAX_ID = 16;

    /**
     * <pre>
     *  (c: continued, e: end)
     *  from Function131, Function134
     *   0 Lc + Lc + Le
     *   1 Rc + Rc + Re
     *  from AudioDataMessage
     *   0 L + R
     * </pre>
     */
    public FuetrekAudioEngine() {
        data = new Data[MAX_ID];
    }

    @Override
    public boolean accept(int format) {
        return format == 0x81; // ADPCM Type2
    }

    @Override
    protected int getChannels(int streamNumber) {
        int channels = 1;
        if (data[streamNumber].channel != -1) {
            // from MachineDependent
            if (streamNumber % 2 == 1 && data[streamNumber].channel % 2 == 1 && (data[streamNumber - 1] != null && data[streamNumber - 1].channel % 2 == 0)) {
logger.log(Level.DEBUG, "always used: no: " + streamNumber + ", ch: " + data[streamNumber].channel);
                return -1;
            }

            if (streamNumber % 2 == 0 && data[streamNumber].channel % 2 == 0 && (data[streamNumber + 1] != null && data[streamNumber + 1].channel % 2 == 1)) {
                channels = 2;
            }
        } else {
            // from AudioData
            // The ADPM subchunk explicitly records whether this stream is mono
            // or stereo.  Consecutive adat chunks are independent streams: in
            // particular, MFi files commonly put a 16 kHz/4-bit stream next to
            // a 32 kHz/2-bit one.  Treating those as an implicit L/R pair both
            // selects the wrong decoder for the right channel and produces
            // audible noise.
            channels = data[streamNumber].channels;
        }
        return channels;
    }

    @Override
    protected InputStream[] getInputStreams(int streamNumber, int channels) {
        InputStream[] iss = new InputStream[2];
        if (data[streamNumber].channels == 1) {
            if (data[streamNumber].bits == 4) {
                InputStream in = new ByteArrayInputStream(data[streamNumber].adpcm);
                iss[0] = get4BitInputStream(in);
                if (channels != 1) {
                    InputStream inR = new ByteArrayInputStream(data[streamNumber + 1].adpcm);
                    iss[1] = get4BitInputStream(inR);
                }
            } else if (data[streamNumber].bits == 2) {
                InputStream in = new ByteArrayInputStream(data[streamNumber].adpcm);
                iss[0] = get2BitInputStream(streamNumber, in);
                if (channels != 1) {
                    InputStream inR = new ByteArrayInputStream(data[streamNumber + 1].adpcm);
                    iss[1] = get2BitInputStream(streamNumber + 1, inR);
                }
            }
        } else {
            if (data[streamNumber].bits == 4) {
                InputStream in = new ByteArrayInputStream(data[streamNumber].adpcm, 0, data[streamNumber].adpcm.length / 2);
                iss[0] = get4BitInputStream(in);
                InputStream inR = new ByteArrayInputStream(data[streamNumber].adpcm, data[streamNumber].adpcm.length / 2, data[streamNumber].adpcm.length / 2);
                iss[1] = get4BitInputStream(inR);
            } else if (data[streamNumber].bits == 2) {
                InputStream in = new ByteArrayInputStream(data[streamNumber].adpcm, 0, data[streamNumber].adpcm.length / 2);
                iss[0] = get2BitInputStream(streamNumber, in);
                InputStream inR = new ByteArrayInputStream(data[streamNumber].adpcm, data[streamNumber].adpcm.length / 2, data[streamNumber].adpcm.length / 2);
                iss[1] = get2BitInputStream(streamNumber + 1, inR);
            }
        }
        return iss;
    }

    @Override
    protected String getDecoderName(int streamNumber, int bits, byte[] adpcm) {
        if (bits == 2) {
            String decoder = configured2BitDecoder(streamNumber);
            if (decoder.equals("auto")) {
                try {
                    return auto2BitDecoder(adpcm);
                } catch (IOException e) {
                    return "auto(error)";
                }
            }
            return decoder;
        }
        if (bits == 4) {
            return System.getProperty("vavi.sound.mobile.FuetrekAudioEngine.decoder", "g721")
                         .toLowerCase(Locale.ROOT);
        }
        return "unknown";
    }

    /** Selects the code-word packing used by a two-bit MFi ADPCM stream. */
    private InputStream get2BitInputStream(int streamNumber, InputStream in) {
        String decoder = configured2BitDecoder(streamNumber);
        if (decoder.equals("ima2") || decoder.equals("ima")) {
            return new Ima2InputStream(in, ByteOrder.LITTLE_ENDIAN);
        }
        if (decoder.equals("auto")) {
            try {
                byte[] compressed = in.readAllBytes();
                String selected = auto2BitDecoder(compressed);
                byte[] decoded = selected.equals("g721") ?
                        decodeAll(new G721InputStream(new ByteArrayInputStream(compressed), ByteOrder.LITTLE_ENDIAN)) :
                        decodeAll(new G723_16InputStream(new ByteArrayInputStream(compressed),
                                                         ByteOrder.LITTLE_ENDIAN,
                                                         ByteOrder.LITTLE_ENDIAN));
                // A few DoCoMo Type-2 resources are tagged as 2-bit but are
                // actually 4-bit G.721 packets.  Their G.723 expansion has
                // near-white-noise roughness; retain G.723 for normal streams.
                return new ByteArrayInputStream(decoded);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        String order = System.getProperty("vavi.sound.mobile.FuetrekAudioEngine.g723BitOrder." + streamNumber);
        if (order == null) {
            order = System.getProperty("vavi.sound.mobile.FuetrekAudioEngine.g723BitOrder", "little");
        }
        order = order
                             .toLowerCase(Locale.ROOT);
        return switch (order) {
        case "little", "le" -> new G723_16InputStream(in, ByteOrder.LITTLE_ENDIAN, ByteOrder.LITTLE_ENDIAN);
        case "big", "be" -> new G723_16InputStream(in, ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN);
        default -> throw new IllegalArgumentException("unsupported G.723 bit order: " + order);
        };
    }

    private static String configured2BitDecoder(int streamNumber) {
        String decoder = System.getProperty("vavi.sound.mobile.FuetrekAudioEngine.g723Decoder." + streamNumber);
        if (decoder == null) {
            decoder = System.getProperty("vavi.sound.mobile.FuetrekAudioEngine.g723Decoder", "auto");
        }
        return decoder.toLowerCase(Locale.ROOT);
    }

    /** Runs the same score used by playback, but without consuming the stored stream. */
    private static String auto2BitDecoder(byte[] compressed) throws IOException {
        byte[] g723 = decodeAll(new G723_16InputStream(new ByteArrayInputStream(compressed),
                                                       ByteOrder.LITTLE_ENDIAN,
                                                       ByteOrder.LITTLE_ENDIAN));
        byte[] g721 = decodeAll(new G721InputStream(new ByteArrayInputStream(compressed),
                                                     ByteOrder.LITTLE_ENDIAN));
        double g723Score = roughness(g723);
        double g721Score = roughness(g721);
        if (g721Score + 0.12 < g723Score) {
            logger.log(Level.DEBUG, "Type-2 ADPCM: selecting G.721 fallback (scores {0}, {1})",
                       g721Score, g723Score);
            return "g721";
        }
        return "g723";
    }

    private static byte[] decodeAll(InputStream in) throws IOException {
        try (InputStream stream = in) {
            return stream.readAllBytes();
        }
    }

    /** Normalized first-difference energy; white-noise-like data scores high. */
    private static double roughness(byte[] pcm) {
        if (pcm.length < 4) return Double.POSITIVE_INFINITY;
        double energy = 0;
        double difference = 0;
        int previous = 0;
        int samples = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            energy += (double) sample * sample;
            if (samples++ != 0) difference += Math.abs(sample - previous);
            previous = sample;
        }
        return difference / Math.max(1, samples - 1) /
               Math.sqrt(energy / Math.max(1, samples));
    }

    /**
     * Selects the 4-bit decoder.  MFi Type 2 is normally CCITT G.721; the
     * alternate decoders are intentionally opt-in so anomalous legacy files
     * can be auditioned without changing normal playback.
     */
    private InputStream get4BitInputStream(InputStream in) {
        String decoder = System.getProperty("vavi.sound.mobile.FuetrekAudioEngine.decoder", "g721")
                               .toLowerCase(Locale.ROOT);
        return switch (decoder) {
        case "g721" -> new G721InputStream(in, ByteOrder.LITTLE_ENDIAN);
        case "yamaha" -> new YamahaInputStream(in, ByteOrder.LITTLE_ENDIAN);
        case "ma" -> new MaInputStream(in, ByteOrder.LITTLE_ENDIAN);
        case "dvi" -> new DviInputStream(in, ByteOrder.LITTLE_ENDIAN);
        case "oki" -> new OkiInputStream(in, ByteOrder.LITTLE_ENDIAN);
        case "rohm" -> new RohmInputStream(in, ByteOrder.LITTLE_ENDIAN);
        case "vox" -> new VoxInputStream(in, ByteOrder.LITTLE_ENDIAN);
        default -> throw new IllegalArgumentException("unsupported 4-bit decoder: " + decoder);
        };
    }

    // ----

    @Override
    protected OutputStream getOutputStream(OutputStream os) {
        return new G721OutputStream(os, ByteOrder.LITTLE_ENDIAN);
    }
}
