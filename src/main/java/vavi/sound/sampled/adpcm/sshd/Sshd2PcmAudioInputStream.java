/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.adpcm.sshd;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

import vavi.sound.adpcm.sshd.Sshd.Codec;
import vavi.sound.adpcm.sshd.SshdInputStream;


/**
 * Converts a SShd (Sony "Audio Stream") body into a PCM 16bits/sample audio stream.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-11 nsano initial version <br>
 */
class Sshd2PcmAudioInputStream extends AudioInputStream {

    /**
     * Constructor.
     *
     * @param in the underlying input stream, positioned at the body of the SShd stream.
     * @param format the target format of this stream's audio data.
     * @param length the length in sample frames of the data in this stream.
     * @param codec the codec the body is encoded with.
     * @param channels the number of channels of the source stream.
     * @param interleaveBlockSize bytes per channel per interleave block set.
     * @param streamSize usable bytes of the body.
     */
    public Sshd2PcmAudioInputStream(InputStream in, AudioFormat format, long length,
                                    Codec codec, int channels, int interleaveBlockSize, int streamSize)
        throws IOException {

        super(new SshdInputStream(in, codec, channels, interleaveBlockSize, streamSize, ByteOrder.LITTLE_ENDIAN),
                format, length);
    }
}
