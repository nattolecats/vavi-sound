/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.adpcm.sshd;

import javax.sound.sampled.AudioFileFormat;


/**
 * FileFormatTypes used by the SShd (Sony "Audio Stream") audio decoder.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-11 nsano initial version <br>
 */
public class SshdFileFormatType extends AudioFileFormat.Type {

    /**
     * Specifies a SShd file. {@code .ads} is the official extension,
     * {@code .ss2} the one of demuxed videos.
     */
    public static final AudioFileFormat.Type SSHD = new SshdFileFormatType("SSHD", "ads");

    /**
     * Constructs a file type.
     *
     * @param name the name of the SShd File Format.
     * @param extension the file extension for this SShd File Format.
     */
    private SshdFileFormatType(String name, String extension) {
        super(name, extension);
    }
}
