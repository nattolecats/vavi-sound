package vavi.sound.mfi.vavi.faith;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;


class FaithType4RendererTest {

    @Test
    void defaultDllIsRtPlayerType4() {
        assertEquals(Path.of("C:\\Program Files (x86)\\Faith\\Ring Tone Authoring Tool\\Tools\\rt_synth_4.dll"),
                     FaithType4Renderer.defaultDll());
    }

    @Test
    void writesShortMessagesAtTempoMappedFrames() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 100);
        var track = sequence.createTrack();
        ShortMessage program = new ShortMessage();
        program.setMessage(ShortMessage.PROGRAM_CHANGE, 2, 40, 0);
        track.add(new MidiEvent(program, 0));
        ShortMessage on = new ShortMessage();
        on.setMessage(ShortMessage.NOTE_ON, 2, 60, 100);
        track.add(new MidiEvent(on, 100));
        MetaMessage tempo = new MetaMessage();
        tempo.setMessage(0x51, new byte[] { 0x03, (byte) 0xd0, (byte) 0x90 }, 3); // 250 ms/quarter
        track.add(new MidiEvent(tempo, 100));
        ShortMessage off = new ShortMessage();
        off.setMessage(ShortMessage.NOTE_OFF, 2, 60, 0);
        track.add(new MidiEvent(off, 200));

        Path events = Files.createTempFile("faith-type4-events-", ".tsv");
        try {
            assertEquals(33_075, FaithType4Renderer.writeEvents(sequence, events, 0));
            assertEquals(List.of(
                    "0\t194\t40\t0",
                    "22050\t146\t60\t100",
                    "33075\t130\t60\t0"), Files.readAllLines(events));
        } finally {
            Files.deleteIfExists(events);
        }
    }
}
