package vavi.sound.mobile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;


/** Regression tests for MFi4 ADPM stream channel selection. */
class FuetrekAudioEngineTest {

    @Test
    void keepsAdjacentMonoAudioDataStreamsIndependent() {
        FuetrekAudioEngine engine = new FuetrekAudioEngine();
        engine.data[0] = audioData(16000, 4);
        engine.data[1] = audioData(32000, 2);

        assertEquals(1, engine.getChannels(0));
        assertEquals(1, engine.getChannels(1));
    }

    @Test
    void honoursStereoFlagInAdpmChunk() {
        FuetrekAudioEngine engine = new FuetrekAudioEngine();
        AudioEngine.Data stream = audioData(16000, 4);
        stream.channels = 2;
        engine.data[0] = stream;

        assertEquals(2, engine.getChannels(0));
    }

    @Test
    void type2TwoBitHonoursExplicitCodecSelection() throws Exception {
        String previous = System.getProperty("vavi.sound.mobile.FuetrekAudioEngine.g723Decoder");
        try {
            FuetrekAudioEngine engine = new FuetrekAudioEngine();
            AudioEngine.Data stream = audioData(32000, 2);
            stream.adpcm = new byte[] { 0, 0 };
            engine.data[0] = stream;

            System.setProperty("vavi.sound.mobile.FuetrekAudioEngine.g723Decoder", "ima2");
            assertEquals("Ima2InputStream", engine.getInputStreams(0, 1)[0].getClass().getSimpleName());
            System.setProperty("vavi.sound.mobile.FuetrekAudioEngine.g723Decoder", "g723");
            assertEquals("G723_16InputStream", engine.getInputStreams(0, 1)[0].getClass().getSimpleName());
        } finally {
            if (previous == null) {
                System.clearProperty("vavi.sound.mobile.FuetrekAudioEngine.g723Decoder");
            } else {
                System.setProperty("vavi.sound.mobile.FuetrekAudioEngine.g723Decoder", previous);
            }
        }
    }

    private static AudioEngine.Data audioData(int rate, int bits) {
        AudioEngine.Data result = new AudioEngine.Data();
        result.channel = -1;
        result.sampleRate = rate;
        result.bits = bits;
        result.channels = 1;
        result.adpcm = new byte[] { 0 };
        return result;
    }
}
