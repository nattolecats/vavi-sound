/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.adpcm.sshd;

import javax.sound.sampled.AudioFormat;


/**
 * Encodings used by the SShd (Sony "Audio Stream") decoder.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-11 nsano initial version <br>
 */
public class SshdEncoding extends AudioFormat.Encoding {

    /** Specifies any SShd encoded data. */
    public static final SshdEncoding SSHD = new SshdEncoding("SSHD");

    /**
     * Constructs a new encoding.
     *
     * @param name Name of the SShd encoding.
     */
    private SshdEncoding(String name) {
        super(name);
    }
}
