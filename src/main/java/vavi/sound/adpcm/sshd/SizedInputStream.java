/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.adpcm.sshd;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;


/**
 * An {@link InputStream} that ends after a fixed number of bytes.
 * <p>
 * A SShd body is followed by padding and by trailing frames that must not be decoded,
 * so the decoder is fed a stream cut at {@link Sshd#streamSize}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-11 nsano initial version <br>
 */
class SizedInputStream extends FilterInputStream {

    /** bytes left to be read */
    private long remaining;

    /**
     * @param in the underlying stream, positioned at the first byte to be read
     * @param size number of bytes to read from {@code in}
     */
    SizedInputStream(InputStream in, long size) {
        super(in);
        if (size < 0) {
            throw new IllegalArgumentException("size " + size + " makes no sense");
        }
        this.remaining = size;
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int c = in.read();
        if (c != -1) {
            remaining--;
        }
        return c;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int r = in.read(b, off, (int) Math.min(len, remaining));
        if (r > 0) {
            remaining -= r;
        }
        return r;
    }

    @Override
    public long skip(long n) throws IOException {
        long r = in.skip(Math.min(n, remaining));
        remaining -= r;
        return r;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(in.available(), remaining);
    }

    @Override
    public boolean markSupported() {
        return false;
    }
}
