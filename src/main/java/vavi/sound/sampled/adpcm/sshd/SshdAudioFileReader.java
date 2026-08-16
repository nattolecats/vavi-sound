/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.adpcm.sshd;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.spi.AudioFileReader;

import vavi.sound.adpcm.sshd.Sshd;

import static java.lang.System.getLogger;


/**
 * Provider for SShd (Sony "Audio Stream", a.k.a. ADS) audio file reading services.
 * <p>
 * The audio input streams returned are positioned at the body of the stream and carry the
 * {@link SshdEncoding#SSHD} encoding, {@link SshdFormatConversionProvider} decodes them into
 * PCM. The {@link AudioFormat} holds the properties needed for that, plus a few informative
 * ones:
 * <ul>
 *  <li>{@code codec}: the {@link Sshd.Codec} the body is encoded with</li>
 *  <li>{@code interleave}: bytes per channel per interleave block set</li>
 *  <li>{@code streamSize}: usable bytes of the body</li>
 *  <li>{@code startOffset}: where the body starts in the file</li>
 *  <li>{@code numSamples}: samples per channel</li>
 *  <li>{@code loopStart}, {@code loopEnd}: loop points in samples, only when a loop was detected</li>
 * </ul>
 * <p>
 * Where the body starts and how much of it is usable can only be told from the size of the
 * whole file, so the {@link InputStream} overloads use {@link InputStream#available()} as the
 * size and need to be given a stream positioned at the very start of the data.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-11 nsano initial version <br>
 */
public class SshdAudioFileReader extends AudioFileReader {

    private static final Logger logger = getLogger(SshdAudioFileReader.class.getName());

    @Override
    public AudioFileFormat getAudioFileFormat(File file) throws UnsupportedAudioFileException, IOException {
        return toAudioFileFormat(analyze(file.toPath()));
    }

    @Override
    public AudioFileFormat getAudioFileFormat(URL url) throws UnsupportedAudioFileException, IOException {
        Path path = toPath(url);
        if (path != null) {
            return toAudioFileFormat(analyze(path));
        }
        try (InputStream stream = new BufferedInputStream(url.openStream())) {
            return toAudioFileFormat(analyze(stream, nameOf(url.getPath())));
        }
    }

    @Override
    public AudioFileFormat getAudioFileFormat(InputStream stream) throws UnsupportedAudioFileException, IOException {
        return toAudioFileFormat(analyze(stream, null));
    }

    @Override
    public AudioInputStream getAudioInputStream(File file) throws UnsupportedAudioFileException, IOException {
        Sshd sshd = analyze(file.toPath());
        InputStream stream = new BufferedInputStream(Files.newInputStream(file.toPath()));
        try {
            return toAudioInputStream(stream, sshd);
        } catch (IOException e) {
            stream.close();
            throw e;
        }
    }

    @Override
    public AudioInputStream getAudioInputStream(URL url) throws UnsupportedAudioFileException, IOException {
        Path path = toPath(url);
        if (path != null) {
            return getAudioInputStream(path.toFile());
        }
        InputStream stream = new BufferedInputStream(url.openStream());
        try {
            return toAudioInputStream(stream, analyze(stream, nameOf(url.getPath())));
        } catch (UnsupportedAudioFileException | IOException e) {
            stream.close();
            throw e;
        }
    }

    @Override
    public AudioInputStream getAudioInputStream(InputStream stream) throws UnsupportedAudioFileException, IOException {
        return toAudioInputStream(stream, analyze(stream, null));
    }

    /** analysis of a file, the whole of it is available so all the heuristics can be applied */
    private static Sshd analyze(Path path) throws UnsupportedAudioFileException, IOException {
        try {
            return Sshd.analyze(path);
        } catch (IllegalArgumentException e) {
logger.log(Level.DEBUG, path + ": " + e.getMessage());
            throw (UnsupportedAudioFileException) new UnsupportedAudioFileException(path.toString()).initCause(e);
        }
    }

    /** analysis of a stream, it is left where it was */
    private static Sshd analyze(InputStream stream, String filename) throws UnsupportedAudioFileException, IOException {
        try {
            return Sshd.analyze(stream, stream.available(), filename);
        } catch (IllegalArgumentException e) {
logger.log(Level.DEBUG, "stream: " + e.getMessage());
            throw (UnsupportedAudioFileException) new UnsupportedAudioFileException(String.valueOf(e.getMessage())).initCause(e);
        }
    }

    /** */
    private static AudioFileFormat toAudioFileFormat(Sshd sshd) {
        return new AudioFileFormat(SshdFileFormatType.SSHD, toAudioFormat(sshd), sshd.numSamples);
    }

    /** skips to the body and wraps it, the encoded stream is decoded by {@link SshdFormatConversionProvider} */
    private static AudioInputStream toAudioInputStream(InputStream stream, Sshd sshd) throws IOException {
        stream.skipNBytes(sshd.startOffset);
        return new AudioInputStream(stream, toAudioFormat(sshd), AudioSystem.NOT_SPECIFIED);
    }

    /** */
    private static AudioFormat toAudioFormat(Sshd sshd) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("codec", sshd.codec);
        properties.put("interleave", sshd.interleaveBlockSize);
        properties.put("streamSize", sshd.streamSize);
        properties.put("startOffset", sshd.startOffset);
        properties.put("numSamples", sshd.numSamples);
        if (sshd.loopFlag) {
            properties.put("loopStart", sshd.loopStartSample);
            properties.put("loopEnd", sshd.loopEndSample);
        }

        return new AudioFormat(SshdEncoding.SSHD,
                sshd.sampleRate,
                AudioSystem.NOT_SPECIFIED,
                sshd.channels,
                AudioSystem.NOT_SPECIFIED,
                AudioSystem.NOT_SPECIFIED,
                false,
                properties);
    }

    /** @return null when the url is not a local file */
    private static Path toPath(URL url) {
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException e) {
logger.log(Level.TRACE, "not a local file: " + url);
            return null;
        }
    }

    /** */
    private static String nameOf(String path) {
        int p = path.lastIndexOf('/');
        return p < 0 ? path : path.substring(p + 1);
    }
}
