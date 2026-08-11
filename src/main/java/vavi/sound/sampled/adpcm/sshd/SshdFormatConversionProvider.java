/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.adpcm.sshd;

import java.io.IOException;
import java.io.UncheckedIOException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.spi.FormatConversionProvider;

import vavi.sound.adpcm.sshd.Sshd.Codec;


/**
 * SshdFormatConversionProvider.
 * <p>
 * SShd (Sony "Audio Stream") to PCM only, there is no encoder.
 * The source format must carry the properties set by {@link SshdAudioFileReader}: {@code codec},
 * {@code interleave} (bytes per channel per interleave block set) and {@code streamSize}
 * (usable bytes of the body).
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-11 nsano initial version <br>
 */
public class SshdFormatConversionProvider extends FormatConversionProvider {

    @Override
    public AudioFormat.Encoding[] getSourceEncodings() {
        return new AudioFormat.Encoding[] { SshdEncoding.SSHD };
    }

    @Override
    public AudioFormat.Encoding[] getTargetEncodings() {
        return new AudioFormat.Encoding[] { AudioFormat.Encoding.PCM_SIGNED };
    }

    @Override
    public AudioFormat.Encoding[] getTargetEncodings(AudioFormat sourceFormat) {
        if (sourceFormat.getEncoding() instanceof SshdEncoding) {
            return new AudioFormat.Encoding[] { AudioFormat.Encoding.PCM_SIGNED };
        } else {
            return new AudioFormat.Encoding[0];
        }
    }

    @Override
    public AudioFormat[] getTargetFormats(AudioFormat.Encoding targetEncoding, AudioFormat sourceFormat) {
        if (sourceFormat.getEncoding() instanceof SshdEncoding && targetEncoding.equals(AudioFormat.Encoding.PCM_SIGNED)) {
            return new AudioFormat[] {
                new AudioFormat(sourceFormat.getSampleRate(),
                                16,         // sample size in bits
                                sourceFormat.getChannels(),
                                true,       // signed
                                false)      // little endian
            };
        } else {
            return new AudioFormat[0];
        }
    }

    @Override
    public AudioInputStream getAudioInputStream(AudioFormat.Encoding targetEncoding, AudioInputStream sourceStream) {
        if (isConversionSupported(targetEncoding, sourceStream.getFormat())) {
            AudioFormat[] formats = getTargetFormats(targetEncoding, sourceStream.getFormat());
            if (formats != null && formats.length > 0) {
                return getAudioInputStream(formats[0], sourceStream);
            } else {
                throw new IllegalArgumentException("target format not found");
            }
        } else {
            throw new IllegalArgumentException("conversion not supported");
        }
    }

    @Override
    public AudioInputStream getAudioInputStream(AudioFormat targetFormat, AudioInputStream sourceStream) {
        if (isConversionSupported(targetFormat, sourceStream.getFormat())) {
            AudioFormat sourceFormat = sourceStream.getFormat();
            if (sourceFormat.equals(targetFormat)) {
                return sourceStream;
            } else if (sourceFormat.getEncoding() instanceof SshdEncoding &&
                       targetFormat.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)) {
                try {
                    return new Sshd2PcmAudioInputStream(sourceStream, targetFormat, frameLengthOf(sourceFormat),
                            codecOf(sourceFormat), sourceFormat.getChannels(),
                            intOf(sourceFormat, "interleave"), intOf(sourceFormat, "streamSize"));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                throw new IllegalArgumentException("unable to convert " + sourceFormat + " to " + targetFormat);
            }
        } else {
            throw new IllegalArgumentException("conversion not supported");
        }
    }

    /** the "codec" property of the source format */
    private static Codec codecOf(AudioFormat sourceFormat) {
        Object value = sourceFormat.getProperty("codec");
        if (value instanceof Codec codec) {
            return codec;
        }
        throw new IllegalArgumentException("no codec property: " + value);
    }

    /** an int property of the source format */
    private static int intOf(AudioFormat sourceFormat, String name) {
        Object value = sourceFormat.getProperty(name);
        if (value instanceof Integer i) {
            return i;
        }
        throw new IllegalArgumentException("no " + name + " property: " + value);
    }

    /** the "numSamples" property of the source format, unknown when absent */
    private static long frameLengthOf(AudioFormat sourceFormat) {
        Object value = sourceFormat.getProperty("numSamples");
        return value instanceof Integer i ? i : AudioSystem.NOT_SPECIFIED;
    }
}
