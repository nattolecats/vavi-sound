/*
 * https://github.com/vgmstream/vgmstream/blob/master/src/meta/sshd.c
 */

package vavi.sound.adpcm.sshd;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import vavi.io.SeekableDataInputStream;
import vavi.sound.adpcm.psx.Psx;

import static java.lang.System.getLogger;


/**
 * Sony "Audio Stream" (SShd/SSbd, a.k.a. ADS) header.
 * <p>
 * The container is a plain {@code SShd} header followed by a {@code SSbd} body holding
 * PCM16LE, PS-ADPCM or (for a few video rips) DVI/IMA ADPCM data, laid out in interleave
 * blocks of {@link #interleaveBlockSize} bytes per channel.
 * <p>
 * {@code .ss2} (demuxed videos) is one of the many extensions this header is found with,
 * see {@link #EXTENSIONS}.
 * <p>
 * Devs hacked this format a lot, so detection of the start offset, the real body size and
 * the loop points is heuristic, exactly as in the original vgmstream meta this is ported
 * from. Those heuristics need random access to the whole file; when analysis is done over
 * a plain stream ({@link #analyze(InputStream, int, String)}) the "ADSC" alignment and the
 * PS-ADPCM padding frame trimming are skipped.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-11 nsano initial version <br>
 */
public class Sshd {

    private static final Logger logger = getLogger(Sshd.class.getName());

    /** used when the size of the source is unknown */
    public static final int UNKNOWN_SIZE = -1;

    /** the header is 0x28 bytes: "SShd" part (0x20) plus the "SSbd" body header (0x08) */
    public static final int HEADER_SIZE = 0x28;

    /**
     * extensions this header is found with
     * <ul>
     *  <li>{@code ads}: actual extension</li>
     *  <li>{@code ss2}: demuxed videos (fake?)</li>
     *  <li>{@code pcm}: Taisho Mononoke Ibunroku (PS2)</li>
     *  <li>{@code adx}: Armored Core 3 (PS2)</li>
     *  <li>(extensionless): MotoGP (PS2)</li>
     *  <li>{@code 800}: Mobile Suit Gundam: The One Year War (PS2)</li>
     *  <li>{@code sdl}: Innocent Life: A Futuristic Harvest Moon (Special Edition) (PS2)</li>
     * </ul>
     */
    public static final List<String> EXTENSIONS = List.of("ads", "ss2", "pcm", "adx", "", "800", "sdl");

    /** codecs the body may be encoded with */
    public enum Codec {
        /** 16 bit little endian pcm */
        PCM16LE,
        /** PlayStation ADPCM */
        PSX,
        /** DVI/IMA ADPCM, one stream per channel (Angel Studios/Rockstar San Diego videos) */
        DVI_IMA
    }

    /** codec of the body */
    public Codec codec;
    /** sampling rate [Hz] */
    public int sampleRate;
    /** number of channels, up to 4 [Eve of Extinction (PS2)] */
    public int channels;
    /** bytes per channel per interleave block set, set even when mono */
    public int interleaveBlockSize;
    /** where the body starts, absolute */
    public int startOffset;
    /** usable body size in bytes (padding frames excluded) */
    public int streamSize;
    /** samples per channel */
    public int numSamples;
    /** whether loop points were detected */
    public boolean loopFlag;
    /** loop start in samples per channel */
    public int loopStartSample;
    /** loop end in samples per channel */
    public int loopEndSample;

    /** raw codec id of the header, for diagnostics */
    private int rawCodec;

    /** cavia games write silent frames as padding [Drakengard 1/2, GITS: Stand Alone Complex] */
    private boolean ignoreSilentFrameCavia;

    /** capcom games write silent frames as padding [Mega Man X7, Breath of Fire V, Clock Tower 3] */
    private boolean ignoreSilentFrameCapcom;

    /** use the factory methods */
    private Sshd() {
    }

    /**
     * Analyzes a file.
     *
     * @param path the file to analyze
     * @throws IllegalArgumentException when the file is not a SShd one
     */
    public static Sshd analyze(Path path) throws IOException {
        try (SeekableDataInputStream sf = new SeekableDataInputStream(Files.newByteChannel(path))) {
            return analyze(new SeekableSource(sf), (int) Files.size(path), path.getFileName().toString());
        }
    }

    /**
     * Analyzes a stream. Only the header is read (the stream must be positioned at its start and
     * support {@link InputStream#mark}), so the heuristics that need random access are skipped.
     *
     * @param in the stream to analyze, it is left at its original position
     * @param fileSize size of the whole stream, {@link #UNKNOWN_SIZE} when unknown. It is only a
     *                 hint but a wrong one makes the start offset detection fail.
     * @param filename name to check the extension of, null to skip that check
     * @throws IllegalArgumentException when the stream is not a SShd one
     */
    public static Sshd analyze(InputStream in, int fileSize, String filename) throws IOException {
        if (!in.markSupported()) {
            throw new IllegalArgumentException("stream must support mark");
        }
        byte[] header = new byte[HEADER_SIZE];
        in.mark(HEADER_SIZE);
        try {
            int r = in.readNBytes(header, 0, HEADER_SIZE);
            if (r < HEADER_SIZE) {
                throw new IllegalArgumentException("too short: " + r);
            }
        } finally {
            in.reset();
        }
        return analyze(new HeaderSource(header), fileSize, filename);
    }

    /** port of {@code init_vgmstream_sshd} (plus {@code init_vgmstream_sshd_container}) */
    private static Sshd analyze(Source source, int fileSize, String filename) throws IOException {
        Sshd sshd = new Sshd();

        // "ADS in containers", the sshd data is a subfile of those
        int base = containerOffset(source);
        if (base > 0) {
            if (!source.randomAccess()) {
                throw new IllegalArgumentException("container needs a random accessible source");
            }
            if (fileSize != UNKNOWN_SIZE) {
                fileSize -= base;
            }
logger.log(Level.DEBUG, "container: subfile at %#x".formatted(base));
        }

        byte[] header = source.read(base, HEADER_SIZE);

        // checks
        if (!isId(header, 0x00, "SShd")) {
            throw new IllegalArgumentException("not a SShd header");
        }
        if (!isId(header, 0x20, "SSbd")) {
            throw new IllegalArgumentException("no SSbd body");
        }

        int headerSize = u32le(header, 0x04);
        if (headerSize != 0x18 &&                                   // standard header size
            headerSize != 0x20 &&                                   // True Fortune (PS2)
            !(fileSize != UNKNOWN_SIZE && headerSize == fileSize - 0x08)) { // Katamari Damacy videos
            throw new IllegalArgumentException("unexpected header size: " + headerSize);
        }

        if (filename != null) {
            String extension = extensionOf(filename);
            if (!EXTENSIONS.contains(extension)) {
                throw new IllegalArgumentException("unexpected extension: " + extension);
            }
        }

        // base values (a bit unorderly since devs hack ADS too much and detection is messy)
        int codec = u32le(header, 0x08);
        sshd.rawCodec = codec;
        sshd.sampleRate = u32le(header, 0x0c);
        sshd.channels = u32le(header, 0x10);    // up to 4 [Eve of Extinction (PS2)]
        sshd.interleaveBlockSize = u32le(header, 0x14); // set even when mono

        switch (codec) {
        case 0x01:         // official definition
        case 0x80000001:   // [Evergrace II (PS2), but not other From Soft games]
            sshd.codec = Codec.PCM16LE;

            // Angel Studios/Rockstar San Diego videos codec hijack [Red Dead Revolver (PS2), Spy Hunter 2 (PS2)]
            if (sshd.sampleRate == 12000 && sshd.interleaveBlockSize == 0x200) {
                sshd.sampleRate = 48000;
                sshd.interleaveBlockSize = 0x40;
                sshd.codec = Codec.DVI_IMA;
                // should try to detect IMA data but it's not so easy, this works ok since
                // no known games use these settings, videos normally are 48000/24000hz
            }
            break;

        case 0x10: // official definition
        case 0x02: // Capcom games extension, stereo only [Megaman X7 (PS2), Breath of Fire V (PS2), Clock Tower 3 (PS2)]
            sshd.codec = Codec.PSX;
            break;

        case 0x00: // PCM16BE from official docs, probably never used
        default:
            throw new IllegalArgumentException("unknown codec: " + codec);
        }

        if (sshd.channels <= 0 || sshd.channels > 8) { // div by zero and sanity
            throw new IllegalArgumentException("unexpected channels: " + sshd.channels);
        }
        int interleaveUnit = sshd.codec == Codec.PCM16LE ? 0x02 : 0x10;
        if (sshd.interleaveBlockSize < interleaveUnit || sshd.interleaveBlockSize % interleaveUnit != 0) {
            throw new IllegalArgumentException("unexpected interleave: " + sshd.interleaveBlockSize);
        }

        // sizes
        int bodySize = u32le(header, 0x24);
        if (fileSize != UNKNOWN_SIZE) {
            // bigger than fileSize in rare cases, even if containing all data (ex. Megaman X7's SY04.ADS)
            if (bodySize + HEADER_SIZE > fileSize) {
                bodySize = fileSize - HEADER_SIZE;
            }

            // True Fortune: weird stream size
            if (bodySize * 2 == fileSize - 0x18) {
                bodySize = (bodySize * 2) - 0x10;
            }
        }
        sshd.streamSize = bodySize;

        // offset
        sshd.startOffset = HEADER_SIZE;

        // start padding (body size is ok, may have end padding) [Evergrace II (PS2), Armored Core 3 (PS2)]
        //  detection depends on files being properly ripped, so broken/cut files won't play ok
        if (fileSize != UNKNOWN_SIZE && fileSize - bodySize >= 0x800) {
            sshd.startOffset = 0x800; // aligned to sector

            // too much end padding, happens in Super Galdelic Hour's SEL.ADS, maybe in bad rips too
            if (fileSize - bodySize > 0x8000) {
logger.log(Level.DEBUG, "big end padding %#x".formatted(fileSize - bodySize));
            }
        }

        // "ADSC" container
        if (sshd.codec == Codec.PSX && source.randomAccess() && isAdsc(source, base)) {
            sshd.startOffset = 0x1000 - 0x08; // remove "ADSC" alignment
            // streamSize doesn't count start offset padding
        }

        // loops
        loops(sshd, source, base, header, codec, bodySize);

        // most games have empty PS-ADPCM frames in the last interleave block that should be skipped
        // for smooth looping
        if (sshd.codec == Codec.PSX && source.randomAccess()) {
            trimPaddingFrames(sshd, source, base);
        }

        sshd.startOffset += base;

        sshd.numSamples = sshd.bytesToSamples(sshd.streamSize);

        if (sshd.loopFlag) {
            // when loop_end = 0xFFFFFFFF
            if (sshd.loopEndSample == 0) {
                sshd.loopEndSample = sshd.numSamples;
            }
            // happens even when loops are directly samples, loops sound fine (ex. Culdcept)
            if (sshd.loopEndSample > sshd.numSamples) {
                sshd.loopEndSample = sshd.numSamples;
            }
        }

logger.log(Level.DEBUG, sshd.toString());
        return sshd;
    }

    /** loop point detection, they are stored in whatever unit each maker felt like using */
    private static void loops(Sshd sshd, Source source, int base, byte[] header, int codec, int bodySize)
        throws IOException {

        int loopStart = u32le(header, 0x18);
        int loopEnd = u32le(header, 0x1c);
        int loopStartOffset = 0;
        int loopEndOffset = 0;
        boolean isLoopSamples = false;

        // detect loops the best we can; docs say those are loop block addresses,
        // but each maker does whatever (no games seem to use PS-ADPCM loop flags though)

        if (loopStart != 0xFFFFFFFF && loopEnd == 0xFFFFFFFF) {

            if (codec == 0x02) { // Capcom codec
                // Capcom games: loopStart is address * 0x10 [Mega Man X7, Breath of Fire V, Clock Tower 3]
                sshd.loopFlag = (loopStart * 0x10) + 0x200 < bodySize; // near the end (+0x20~80) means no loop
                loopStartOffset = loopStart * 0x10;
                sshd.ignoreSilentFrameCapcom = true;
            } else if (source.randomAccess() && isId(source.read(base + HEADER_SIZE, 0x04), 0x00, "PAD!")) {
                // Super Galdelic Hour: loopStart is PCM bytes, padding until 0x800
                sshd.loopFlag = true;
                sshd.loopStartSample = loopStart / 2 / sshd.channels;
                isLoopSamples = true;
            } else if (loopStart % 0x800 == 0 && loopStart > 0) { // sector-aligned, min/0 is 0x800
                // cavia games: loopStart is offset [Drakengard 1/2, GITS: Stand Alone Complex]
                // offset is absolute from the "cavia stream format" container that adjusts ADS start
                sshd.loopFlag = true;
                loopStartOffset = loopStart - 0x800;
                sshd.ignoreSilentFrameCavia = true;
            } else if (loopStart % 0x800 != 0 || loopStart == 0) { // not sector aligned
                // Katakamuna: loopStart is address * 0x10
                sshd.loopFlag = true;
                loopStartOffset = loopStart * 0x10;
            }
        } else if (loopStart != 0xFFFFFFFF && loopEnd != 0xFFFFFFFF && loopEnd > 0) { // ignore Kamen Rider Blade and others
            if (loopEnd <= bodySize / 0x200 && sshd.codec == Codec.PCM16LE) { // close to bodySize
                // Gofun-go no Sekai: loops is address * 0x200
                sshd.loopFlag = true;
                loopStartOffset = loopStart * 0x200;
                loopEndOffset = loopEnd * 0x200;
            } else if (loopEnd <= bodySize / 0x70 && sshd.codec == Codec.PCM16LE) { // close to bodySize
                // Armored Core - Nexus: loops is address * 0x70
                sshd.loopFlag = true;
                loopStartOffset = loopStart * 0x70;
                loopEndOffset = loopEnd * 0x70;
            } else if (loopEnd <= bodySize / 0x20 && sshd.codec == Codec.PCM16LE) { // close to bodySize
                // Armored Core - Nine Breaker: loops is address * 0x20
                sshd.loopFlag = true;
                loopStartOffset = loopStart * 0x20;
                loopEndOffset = loopEnd * 0x20;
            } else if (loopEnd <= bodySize / 0x20 && sshd.codec == Codec.PSX) {
                // various games: loops is address * 0x20 [Fire Pro Wrestling Returns, A.C.E. - Another Century's Episode]
                sshd.loopFlag = true;
                loopStartOffset = loopStart * 0x20;
                loopEndOffset = loopEnd * 0x20;
            } else if (loopEnd <= bodySize / 0x10 && sshd.codec == Codec.PSX && source.randomAccess() &&
                       (u32be(source.read(base + HEADER_SIZE + loopEnd * 0x10 + 0x10, 0x04), 0x00) == 0x00077777 ||
                        u32be(source.read(base + HEADER_SIZE + loopEnd * 0x10 + 0x20, 0x04), 0x00) == 0x00077777)) {
                // not-quite-looping sfx, ending with a "non-looping PS-ADPCM end frame" [Kono Aozora ni Yakusoku, Chanter]
                sshd.loopFlag = false;
            } else if ((loopEnd > bodySize / 0x20 && sshd.codec == Codec.PSX) ||
                       (loopEnd > bodySize / 0x70 && sshd.codec == Codec.PCM16LE)) {
                // various games: loops in samples [Eve of Extinction, Culdcept, WWE Smackdown! 3]
                sshd.loopFlag = true;
                sshd.loopStartSample = loopStart;
                sshd.loopEndSample = loopEnd;
                isLoopSamples = true;
            }
        }

        if (sshd.loopFlag && !isLoopSamples) {
            sshd.loopStartSample = sshd.bytesToSamples(loopStartOffset);
            sshd.loopEndSample = sshd.bytesToSamples(loopEndOffset);
        }
    }

    /** cuts the trailing "don't decode" / null / padding / silent PS-ADPCM frames off {@link #streamSize} */
    private static void trimPaddingFrames(Sshd sshd, Source source, int base) throws IOException {
        int offset = base + sshd.startOffset + sshd.streamSize;
        int minOffset = offset - sshd.interleaveBlockSize;
        int frameSize = 0x10 * sshd.channels;

        do {
            offset -= 0x10;
            if (offset < base + sshd.startOffset || sshd.streamSize < frameSize) {
                break;
            }
            byte[] frame = source.read(offset, 0x10);
            if (frame.length < 0x10) {
                break;
            }

            if ((frame[0x01] & 0xff) == 0x07) {
                // ignore don't decode flag/padding frame (most common) [ex. Capcom games]
                sshd.streamSize -= frameSize;
            } else if (u32be(frame, 0x00) == 0 && u32be(frame, 0x04) == 0 &&
                       u32be(frame, 0x08) == 0 && u32be(frame, 0x0c) == 0) {
                // ignore null frame [ex. A.C.E. Another Century Episode 1/2/3]
                sshd.streamSize -= frameSize;
            } else if (u32be(frame, 0x00) == 0x00007777 && u32be(frame, 0x04) == 0x77777777 &&
                       u32be(frame, 0x08) == 0x77777777 && u32be(frame, 0x0c) == 0x77777777) {
                // ignore padding frame [ex. Akane Iro ni Somaru Saka - Parallel]
                sshd.streamSize -= frameSize;
            } else if (sshd.ignoreSilentFrameCavia &&
                       u32be(frame, 0x00) == 0x0C020000 && u32be(frame, 0x04) == 0 &&
                       u32be(frame, 0x08) == 0 && u32be(frame, 0x0c) == 0) {
                // ignore silent frame [ex. cavia games]
                sshd.streamSize -= frameSize;
            } else if (sshd.ignoreSilentFrameCapcom &&
                       u32be(frame, 0x00) == 0x0C010000 && u32be(frame, 0x04) == 0 &&
                       u32be(frame, 0x08) == 0 && u32be(frame, 0x0c) == 0) {
                // ignore silent frame [ex. Capcom games]
                sshd.streamSize -= frameSize;
            } else {
                break; // standard frame
            }
        } while (offset > minOffset);

        // don't bother fixing loopEndOffset since it will be adjusted to numSamples later, if needed
    }

    /** @return true when the padding before 0x1000 is an "ADSC" alignment */
    private static boolean isAdsc(Source source, int base) throws IOException {
        int length = 0x1008 + 0x04 - HEADER_SIZE;
        byte[] padding = source.read(base + HEADER_SIZE, length);
        if (padding.length < length) {
            return false;
        }
        if (u32le(padding, 0x00) != 0x1000 || u32le(padding, 0x04) != 0 || // real start
            u32le(padding, 0x1008 - HEADER_SIZE) == 0) {
            return false;
        }
        // should be empty up to data start
        for (int i = 0; i < 0xFDC / 4; i++) {
            if (u32le(padding, 0x04 + i * 4) != 0) {
                return false;
            }
        }
        return true;
    }

    /** @return offset of the sshd subfile, 0 when the source is not a known container */
    private static int containerOffset(Source source) throws IOException {
        byte[] head = source.read(0x00, 0x0c);
        if (head.length < 0x0c) {
            return 0;
        }
        // Kenka Bancho 2, Kamen Rider Hibiki/Kabuto, Shinjuku no Okami
        if (isId(head, 0x00, "ADSC") && u32le(head, 0x04) == 0x01) {
            return 0x08;
        }
        // cavia games: Drakengard 1/2, Dragon Quest Yangus, GITS: Stand Alone Complex
        if (isId(head, 0x00, "cavi") && isId(head, 0x04, "a st") && isId(head, 0x08, "ream")) {
            return 0x7d8;
        }
        return 0;
    }

    /** bytes of body to samples per channel */
    public static int bytesToSamples(Codec codec, int bytes, int channels) {
        return switch (codec) {
            case PCM16LE -> bytes / channels / 2;
            case PSX -> Psx.bytesToSamples(bytes, channels);
            case DVI_IMA -> bytes / channels * 2;
        };
    }

    /** bytes of this body to samples per channel */
    public int bytesToSamples(int bytes) {
        return bytesToSamples(codec, bytes, channels);
    }

    /** */
    private static String extensionOf(String filename) {
        int p = filename.lastIndexOf('.');
        return (p < 0 ? "" : filename.substring(p + 1)).toLowerCase(Locale.ROOT);
    }

    /** */
    private static boolean isId(byte[] buffer, int offset, String id) {
        for (int i = 0; i < id.length(); i++) {
            if (offset + i >= buffer.length || buffer[offset + i] != (byte) id.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** unsigned 32 bit little endian, as int (values above 2G are rare and only compared as-is) */
    private static int u32le(byte[] buffer, int offset) {
        return ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /** unsigned 32 bit big endian, as int */
    private static int u32be(byte[] buffer, int offset) {
        return ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    @Override
    public String toString() {
        return "Sshd[codec: %s(%#x), sampleRate: %d, channels: %d, interleave: %#x, startOffset: %#x, streamSize: %#x, numSamples: %d, loop: %s(%d..%d)]"
                .formatted(codec, rawCodec, sampleRate, channels, interleaveBlockSize,
                        startOffset, streamSize, numSamples, loopFlag, loopStartSample, loopEndSample);
    }

    /** where the analysis reads from */
    private interface Source {

        /**
         * Reads at most {@code len} bytes at {@code offset}, a shorter array when the source ends
         * before that. Reading outside the header of a non {@link #randomAccess()} source is an error.
         */
        byte[] read(int offset, int len) throws IOException;

        /** whether data outside the header can be read */
        boolean randomAccess();
    }

    /** a whole file */
    private record SeekableSource(SeekableDataInputStream sf) implements Source {

        @Override
        public byte[] read(int offset, int len) throws IOException {
            if (offset < 0 || len < 0) {
                return new byte[0];
            }
            sf.position(offset);
            byte[] buffer = new byte[len];
            int r = sf.readNBytes(buffer, 0, len);
            return r == len ? buffer : Arrays.copyOf(buffer, Math.max(r, 0));
        }

        @Override
        public boolean randomAccess() {
            return true;
        }
    }

    /** the header only */
    private record HeaderSource(byte[] header) implements Source {

        @Override
        public byte[] read(int offset, int len) throws IOException {
            if (offset < 0 || len < 0 || offset + len > header.length) {
                throw new IOException("out of the header: %#x..%#x".formatted(offset, offset + len));
            }
            return Arrays.copyOfRange(header, offset, offset + len);
        }

        @Override
        public boolean randomAccess() {
            return false;
        }
    }
}
