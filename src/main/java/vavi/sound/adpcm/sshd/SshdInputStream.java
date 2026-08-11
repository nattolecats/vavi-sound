/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.adpcm.sshd;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteOrder;

import vavi.io.OutputEngineInputStream;
import vavi.sound.adpcm.psx.PsxInputStream;
import vavi.sound.adpcm.sshd.Sshd.Codec;

import static java.lang.System.getLogger;


/**
 * SShd (Sony "Audio Stream") InputStream.
 * <p>
 * Decodes the body of a SShd stream into 16 bits/sample PCM, whatever
 * {@link Codec codec} it is encoded with.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-11 nsano initial version <br>
 */
public class SshdInputStream extends FilterInputStream {

    private static final Logger logger = getLogger(SshdInputStream.class.getName());

    /** decoded pcm bytes left */
    private int available;

    /**
     * @param in the body of a SShd stream, positioned at {@link Sshd#startOffset}
     * @param sshd the analyzed header
     * @param byteOrder byte order of the decoded PCM
     */
    public SshdInputStream(InputStream in, Sshd sshd, ByteOrder byteOrder) throws IOException {
        this(in, sshd.codec, sshd.channels, sshd.interleaveBlockSize, sshd.streamSize, byteOrder);
    }

    /**
     * @param in the body of a SShd stream, positioned at the first byte of data
     * @param codec codec the body is encoded with
     * @param channels number of channels
     * @param interleaveBlockSize bytes per channel per interleave block set
     * @param streamSize usable bytes of the body
     * @param byteOrder byte order of the decoded PCM
     */
    public SshdInputStream(InputStream in, Codec codec, int channels, int interleaveBlockSize, int streamSize, ByteOrder byteOrder)
        throws IOException {

        super(decoder(new SizedInputStream(in, blockAligned(streamSize, channels * interleaveBlockSize)),
                codec, channels, interleaveBlockSize, byteOrder));

        int bytesPerSample = 2;
        int numSamples = Sshd.bytesToSamples(codec, streamSize, channels);
logger.log(Level.TRACE, "numSamples: " + numSamples);
        this.available = numSamples * channels * bytesPerSample;
logger.log(Level.TRACE, "available: " + available);
    }

    /**
     * The last interleave block set of a stream may be incomplete, in which case the trailing
     * channels are decoded from what follows the usable body (padding frames), exactly as
     * vgmstream does. So the decoder is fed whole block sets and the surplus samples are cut
     * off by {@link #available} instead.
     */
    private static long blockAligned(int streamSize, int blockSetSize) {
        int remainder = streamSize % blockSetSize;
        return remainder == 0 ? streamSize : (long) streamSize - remainder + blockSetSize;
    }

    /** the decoder for the codec, PS-ADPCM is handled by the psx package */
    private static InputStream decoder(InputStream in, Codec codec, int channels, int interleaveBlockSize, ByteOrder byteOrder)
        throws IOException {

        if (codec == Codec.PSX) {
            return new PsxInputStream(in, channels, interleaveBlockSize, byteOrder);
        } else {
            return new OutputEngineInputStream(new SshdOutputEngine(in, codec, channels, interleaveBlockSize, byteOrder));
        }
    }

    @Override
    public int available() throws IOException {
        return Math.max(available, 0);
    }

    @Override
    public int read() throws IOException {
        if (available <= 0) {
            return -1;
        }
        int c = in.read();
        if (c != -1) {
            available--;
        }
        return c;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (available <= 0) {
            return -1;
        }
        int r = in.read(b, off, Math.min(len, available));
        if (r > 0) {
            available -= r;
        }
        return r;
    }
}
