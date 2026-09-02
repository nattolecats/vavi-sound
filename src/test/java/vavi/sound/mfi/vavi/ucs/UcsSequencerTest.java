package vavi.sound.mfi.vavi.ucs;

import org.junit.jupiter.api.Test;

import vavi.sound.mfi.vavi.track.MachineDependentMessage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Tests UCS PCM packet decoding independently of an audio device. */
class UcsSequencerTest {

    @Test
    void storesDefinedLengthAndSignedPcmPacket() throws Exception {
        UcsSequencer sequencer = new UcsSequencer();

        sequencer.sequence(message(0x10, 3, 1, 0, 0, 3, 0, 0, 1, 0, 0, 3));
        sequencer.sequence(message(0x10, 3, 2, 0, 3, 0x80, 0x00, 0x7f));
        sequencer.sequence(message(0x11, 3, 2, 8, 1, 3, 0, 2, 0, 0x20, 0x34, 0x56));
        sequencer.sequence(message(0x12, 3));
        sequencer.sequence(message(0x40, 0x81, 0, 0x18));

        UcsSequencer.Wave wave = UcsSequencer.waveBank().wave(3);
        assertEquals(3, wave.length);
        assertEquals(1, wave.loopStart);
        assertEquals(3, wave.loopEnd);
        assertEquals(0x3456 / 256d, wave.rootPitch);
        assertEquals(24_000, wave.sampleRate);
        assertTrue(wave.enabled);
        assertArrayEquals(new byte[] { (byte) 0x80, 0, 0x7f }, wave.data);
        assertArrayEquals(new byte[] { (byte) 0x81, 0, 0x18 },
                          UcsSequencer.waveBank().part(0x81).data);
    }

    private static MachineDependentMessage message(int function, int... body) throws Exception {
        byte[] message = new byte[2 + body.length];
        message[0] = 0x01;
        message[1] = (byte) function;
        for (int i = 0; i < body.length; i++) {
            message[i + 2] = (byte) body[i];
        }
        MachineDependentMessage result = new MachineDependentMessage();
        result.setMessage(0, message);
        return result;
    }
}
