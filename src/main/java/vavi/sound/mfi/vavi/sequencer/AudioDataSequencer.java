/*
 * Copyright (c) 2007 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.mfi.vavi.sequencer;

import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;

import vavi.sound.mfi.InvalidMfiDataException;
import vavi.sound.mobile.AudioEngine;


/**
 * AudioData message sequencer.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 070119 nsano initial version <br>
 * @since MFi 4.0
 */
public interface AudioDataSequencer {

    /** manufacturer vavi function id for {@link AudioDataSequencer} */
    int SYSEX_FUNCTION_ID_MFi4 = 0x02;

    /** */
    void sequence() throws InvalidMfiDataException;

    /** factory for audio engine */
    class Factory {

        /** */
        /**
         * Engine selected by the most recently received audio-data chunk.
         *
         * <p>MFi data loading and play control may be dispatched on different
         * sequencer threads, so a ThreadLocal loses the selected engine just
         * before {@code AudioPlayMessage} needs it.</p>
         */
        private static volatile AudioEngine audioEngineStore;

        /**
         * Second time or later.
         * @return nullable
         */
        public static AudioEngine getAudioEngine() {
            return audioEngineStore;
        }

        private static final Set<AudioEngine> engines = new HashSet<>();

        /**
         * First time.
         * @return same instance for each format
         * @throws IllegalArgumentException when audio engine not found
         */
        public static AudioEngine getAudioEngine(int format) {
            for (AudioEngine engine : engines) {
                if (engine.accept(format)) {
                    audioEngineStore = engine;
                    return engine;
                }
            }
            throw new IllegalArgumentException("format: " + format);
        }

        static {
            for (AudioEngine engine : ServiceLoader.load(AudioEngine.class)) {
                engines.add(engine);
            }
        }
    }
}
