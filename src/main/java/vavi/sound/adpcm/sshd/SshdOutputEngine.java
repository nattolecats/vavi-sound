/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.adpcm.sshd;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteOrder;

import vavi.io.OutputEngine;
import vavi.sound.adpcm.sshd.Sshd.Codec;


/**
 * SshdOutputEngine.
 * <p>
 * De-interleaves and decodes a SShd body into 16 bit PCM. The body is laid out in interleave
 * block sets, {@code [ch0 block][ch1 block]...}, each block holding {@code interleaveBlockSize}
 * bytes of one channel.
 * <p>
 * {@link Codec#PSX} is not handled here, it is decoded by {@link vavi.sound.adpcm.psx.PsxInputStream}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-11 nsano initial version <br>
 * @see "https://github.com/vgmstream/vgmstream/blob/master/src/coding/ima_decoder.c"
 */
class SshdOutputEngine implements OutputEngine {

    /** IMA ADPCM step size table */
    private static final int[] IMA_STEP_SIZE_TABLE = {
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    };

    /** IMA ADPCM step index adjust table */
    private static final int[] IMA_INDEX_TABLE = {
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    };

    /** */
    private final InputStream in;

    /** */
    private DataOutputStream out;

    private final Codec codec;
    private final int channels;
    private final int interleaveBlockSize;
    private final ByteOrder byteOrder;

    /** one interleave block set: channels * interleaveBlockSize */
    private byte[] packet;
    /** per channel predictor, DVI/IMA only */
    private int[] hist;
    /** per channel step index, DVI/IMA only */
    private int[] stepIndex;

    /**
     * @param in body of a SShd stream, cut at the usable stream size
     * @param codec {@link Codec#PCM16LE} or {@link Codec#DVI_IMA}
     * @param channels number of channels
     * @param interleaveBlockSize bytes per channel per interleave block set
     * @param byteOrder byte order of the decoded PCM
     */
    SshdOutputEngine(InputStream in, Codec codec, int channels, int interleaveBlockSize, ByteOrder byteOrder) {
        if (codec == Codec.PSX) {
            throw new IllegalArgumentException("psx is decoded by PsxInputStream");
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels " + channels + " makes no sense");
        }
        int unit = codec == Codec.PCM16LE ? 0x02 : 0x01;
        if (interleaveBlockSize < unit || interleaveBlockSize % unit != 0) {
            throw new IllegalArgumentException("interleaveBlockSize " + interleaveBlockSize + " makes no sense");
        }
        this.in = in;
        this.codec = codec;
        this.channels = channels;
        this.interleaveBlockSize = interleaveBlockSize;
        this.byteOrder = byteOrder;
    }

    @Override
    public void initialize(OutputStream out) throws IOException {
        if (this.out != null) {
            throw new IOException("Already initialized");
        } else {
            this.out = new DataOutputStream(out);

            packet = new byte[channels * interleaveBlockSize];
            hist = new int[channels];
            stepIndex = new int[channels];
        }
    }

    @Override
    public void execute() throws IOException {
        if (out == null) {
            throw new IOException("Not yet initialized");
        } else {
            int l = in.readNBytes(packet, 0, packet.length);
            if (l <= 0) {
                out.close();
                return;
            }

            // a trailing partial block set: only the bytes complete for all channels are usable
            int blockSize = interleaveBlockSize;
            if (l < packet.length) {
                blockSize = Math.clamp(l - (long) (channels - 1) * interleaveBlockSize, 0, blockSize);
                if (codec == Codec.PCM16LE) {
                    blockSize -= blockSize % 2;
                }
                if (blockSize <= 0) {
                    out.close();
                    return;
                }
            }

            if (codec == Codec.PCM16LE) {
                for (int i = 0; i < blockSize / 2; i++) {
                    for (int ch = 0; ch < channels; ch++) {
                        int p = ch * interleaveBlockSize + i * 2;
                        write((short) ((packet[p] & 0xff) | (packet[p + 1] << 8)));
                    }
                }
            } else {
                for (int i = 0; i < blockSize * 2; i++) {
                    for (int ch = 0; ch < channels; ch++) {
                        // mono layout: consecutive nibbles, high nibble first
                        int p = ch * interleaveBlockSize + i / 2;
                        int shift = (i & 1) == 0 ? 4 : 0;
                        write((short) expandNibble(packet[p], shift, ch));
                    }
                }
            }
        }
    }

    /** decodes one DVI/IMA nibble, updating the state of the channel */
    private int expandNibble(byte data, int shift, int channel) {
        int code = (data >> shift) & 0xf;
        int step = IMA_STEP_SIZE_TABLE[stepIndex[channel]];

        int delta = step >> 3;
        if ((code & 1) != 0) delta += step >> 2;
        if ((code & 2) != 0) delta += step >> 1;
        if ((code & 4) != 0) delta += step;
        if ((code & 8) != 0) delta = -delta;

        int sample = hist[channel] + delta;
        if (sample > 32767) {
            sample = 32767;
        } else if (sample < -32768) {
            sample = -32768;
        }
        hist[channel] = sample;

        int index = stepIndex[channel] + IMA_INDEX_TABLE[code];
        if (index < 0) {
            index = 0;
        } else if (index > 88) {
            index = 88;
        }
        stepIndex[channel] = index;

        return sample;
    }

    /** */
    private void write(short sample) throws IOException {
        if (ByteOrder.BIG_ENDIAN.equals(byteOrder)) {
            out.writeShort(sample);
        } else {
            out.write( sample & 0x00ff);
            out.write((sample & 0xff00) >> 8);
        }
    }

    @Override
    public void finish() throws IOException {
        in.close();
    }
}
