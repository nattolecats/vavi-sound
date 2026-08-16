/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.adpcm.sshd;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import vavi.sound.adpcm.sshd.Sshd.Codec;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/**
 * SshdTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-11 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class SshdTest {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    @Property(name = "adpcm.sshd")
    String adpcm = "src/test/resources/test.ss2";

    @TempDir
    Path dir;

    @BeforeEach
    void setup() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
    }

    @Test
    @DisplayName("a pcm16 body is de-interleaved")
    void test1() throws Exception {
        // 2 channels, 2 interleave block sets of 8 samples each
        ByteBuffer body = ByteBuffer.allocate(2 * 0x10 * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int set = 0; set < 2; set++) {
            for (int ch = 0; ch < 2; ch++) {
                for (int i = 0; i < 8; i++) {
                    body.putShort((short) (ch * 0x100 + set * 8 + i));
                }
            }
        }
        byte[] data = sshd(0x01, 44100, 2, 0x10, body.array());

        Sshd sshd = Sshd.analyze(write(data, "test.ads"));
Debug.println(sshd);
        assertEquals(Codec.PCM16LE, sshd.codec);
        assertEquals(44100, sshd.sampleRate);
        assertEquals(2, sshd.channels);
        assertEquals(0x10, sshd.interleaveBlockSize);
        assertEquals(Sshd.HEADER_SIZE, sshd.startOffset);
        assertEquals(body.capacity(), sshd.streamSize);
        assertEquals(16, sshd.numSamples);
        assertFalse(sshd.loopFlag);

        short[] expected = new short[16 * 2];
        for (int i = 0; i < 16; i++) {
            expected[i * 2] = (short) i;
            expected[i * 2 + 1] = (short) (0x100 + i);
        }
        assertArrayEquals(expected, decode(data, sshd));
    }

    @Test
    @DisplayName("the dvi/ima codec hijack is detected and decoded")
    void test2() throws Exception {
        // videos of a few games claim pcm16 at 12000hz/0x200 but are ima at 48000hz/0x40
        byte[] body = new byte[2 * 0x40];
        body[0] = 0x4c; // channel 0, nibbles 0x4 then 0xc
        byte[] data = sshd(0x01, 12000, 2, 0x200, body);

        Sshd sshd = Sshd.analyze(write(data, "test.ss2"));
Debug.println(sshd);
        assertEquals(Codec.DVI_IMA, sshd.codec);
        assertEquals(48000, sshd.sampleRate);
        assertEquals(0x40, sshd.interleaveBlockSize);
        assertEquals(0x40 * 2, sshd.numSamples); // 2 samples per byte

        short[] pcm = decode(data, sshd);
        assertEquals(sshd.numSamples * sshd.channels, pcm.length);
        // 0x4: step 7, delta +7 -> 7 | 0xc: step 9, delta -10 -> -3 | channel 1 stays silent
        assertEquals(7, pcm[0]);
        assertEquals(0, pcm[1]);
        assertEquals(-3, pcm[2]);
        assertEquals(0, pcm[3]);
    }

    @Test
    @DisplayName("a stream is analyzed like a file")
    void test3() throws Exception {
        byte[] data = sshd(0x10, 48000, 2, 0x20, new byte[0x20 * 2 * 4]);
        // psx padding frames at the end of the body are not decoded
        for (int i = 0; i < 0x20 * 2 * 3; i++) {
            data[Sshd.HEADER_SIZE + i] = (byte) (i % 0x10 == 0 ? 0x02 : i);
        }

        Sshd file = Sshd.analyze(write(data, "test.ads"));
Debug.println(file);
        assertEquals(Codec.PSX, file.codec);
        assertEquals(0x20 * 2 * 3, file.streamSize); // the null frames of the last block set are cut

        try (InputStream in = new ByteArrayInputStream(data)) {
            Sshd stream = Sshd.analyze(in, data.length, null);
            assertEquals(file.codec, stream.codec);
            assertEquals(file.sampleRate, stream.sampleRate);
            assertEquals(file.channels, stream.channels);
            assertEquals(file.interleaveBlockSize, stream.interleaveBlockSize);
            assertEquals(file.startOffset, stream.startOffset);
            // trimming the trailing frames needs random access, so a stream keeps them
            assertEquals(0x20 * 2 * 4, stream.streamSize);
            assertEquals(data.length, in.available()); // the stream was not consumed
        }
    }

    @Test
    @DisplayName("other data is rejected")
    void test4() throws Exception {
        byte[] data = sshd(0x01, 44100, 1, 0x10, new byte[0x10]);

        byte[] notSshd = data.clone();
        notSshd[0] = 'X';
        assertThrows(IllegalArgumentException.class, () -> Sshd.analyze(write(notSshd, "no.ads")));

        byte[] noBody = data.clone();
        noBody[0x20] = 'X';
        assertThrows(IllegalArgumentException.class, () -> Sshd.analyze(write(noBody, "no2.ads")));

        byte[] unknownCodec = data.clone();
        unknownCodec[0x08] = 0x33;
        assertThrows(IllegalArgumentException.class, () -> Sshd.analyze(write(unknownCodec, "no3.ads")));

        // the header is fine but the extension is not one this format is found with
        assertThrows(IllegalArgumentException.class, () -> Sshd.analyze(write(data, "no4.wav")));
    }

    @Test
    @EnabledIf("localPropertiesExists")
    @DisplayName("a real ss2 file is decoded whole")
    void test5() throws Exception {
        Path path = Path.of(adpcm);
        assumeTrue(Files.exists(path), "no sshd test file: " + path);

        Sshd sshd = Sshd.analyze(path);
Debug.println(sshd);
        assertEquals(2, sshd.channels);
        assertEquals(48000, sshd.sampleRate);

        long decoded = 0;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            in.skipNBytes(sshd.startOffset);
            SshdInputStream sis = new SshdInputStream(in, sshd, ByteOrder.LITTLE_ENDIAN);
            byte[] buf = new byte[8192];
            int l;
            while ((l = sis.read(buf, 0, buf.length)) != -1) {
                decoded += l;
            }
        }
Debug.println("decoded: " + decoded);
        assertEquals((long) sshd.numSamples * sshd.channels * 2, decoded);
    }

    /** builds a SShd file */
    static byte[] sshd(int codec, int sampleRate, int channels, int interleave, byte[] body) {
        ByteBuffer bb = ByteBuffer.allocate(Sshd.HEADER_SIZE + body.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("SShd".getBytes());
        bb.putInt(0x18);            // header size
        bb.putInt(codec);
        bb.putInt(sampleRate);
        bb.putInt(channels);
        bb.putInt(interleave);
        bb.putInt(0xFFFFFFFF);      // loop start
        bb.putInt(0xFFFFFFFF);      // loop end
        bb.put("SSbd".getBytes());
        bb.putInt(body.length);
        bb.put(body);
        return bb.array();
    }

    /** */
    private Path write(byte[] data, String filename) throws Exception {
        Path path = dir.resolve(filename);
        Files.write(path, data);
        return path;
    }

    /** */
    private static short[] decode(byte[] data, Sshd sshd) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream in = new ByteArrayInputStream(data)) {
            in.skipNBytes(sshd.startOffset);
            new SshdInputStream(in, sshd, ByteOrder.LITTLE_ENDIAN).transferTo(baos);
        }
        short[] pcm = new short[baos.size() / 2];
        ByteBuffer.wrap(baos.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
        return pcm;
    }
}
