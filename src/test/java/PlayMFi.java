/*
 * Copyright (c) 2009 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.Soundbank;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vavi.sound.mfi.MetaEventListener;
import vavi.sound.mfi.MfiSystem;
import vavi.sound.mfi.Sequence;
import vavi.sound.mfi.Sequencer;
import vavi.sound.mfi.Synthesizer;
import vavi.sound.mfi.vavi.faith.FaithType4Renderer;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import static vavi.sound.midi.MidiUtil.volume;


/**
 * test mfi (raw Mfi API).
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 090913 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
public class PlayMFi {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    static {
        // make sure smaf sequencer and synthesizer select below devices
        System.setProperty("javax.sound.midi.Synthesizer", "#Gervill");
        System.setProperty("javax.sound.midi.Sequencer", "#Real Time Sequencer");
    }

    @Property(name = "vavi.test.volume.midi")
    // A file can omit the optional MFi master-volume message.  The command
    // line player must therefore start at unity gain; otherwise such files
    // remain permanently attenuated while files that do set master volume
    // appear normal.
    float volume = 1.0f;

    @Property(name = "sf2")
    String sf2 = System.getProperty("user.home") + "/Library/Audio/Sounds/Banks/Orchestra/default.sf2";

    /** Faith Type4 renderer DLL (Windows); an empty value uses Faith's default path. */
    @Property(name = "faith4dll")
    String faith4dll = System.getProperty("faith4dll", "");

    @Property
    String mfi = "src/test/resources/test.mfi";

    boolean sfEnabled = false;
    Sequencer sequencer;
    Synthesizer synthesizer;
    Receiver receiver;

    @BeforeEach
    void setup() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
Debug.println("volume: " + volume);

        sequencer = MfiSystem.getSequencer();
Debug.println(sequencer.getClass().getName());
        sequencer.open();

        synthesizer = MfiSystem.getSynthesizer();
        synthesizer.open();
// sf
Path sf2Path = Path.of(sf2);
if (sfEnabled && Files.exists(sf2Path)) {
 Soundbank soundbank = synthesizer.getDefaultSoundbank();
//Instrument[] instruments = synthesizer.getAvailableInstruments();
 Debug.print("---- " + soundbank.getDescription() + " ----");
//Arrays.asList(instruments).forEach(System.err::println);
 synthesizer.unloadAllInstruments(soundbank);
 soundbank = MidiSystem.getSoundbank(sf2Path.toFile());
 synthesizer.loadAllInstruments(soundbank);
//instruments = synthesizer.getAvailableInstruments();
 Debug.print("---- " + soundbank.getDescription() + " ----");
//Arrays.asList(instruments).forEach(System.err::println);
}
        receiver = synthesizer.getReceiver();
        sequencer.getTransmitter().setReceiver(receiver);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (sequencer != null)
            sequencer.close();
    }

    @Test
    void test1() throws Exception {
        exec();
    }

    /** */
    void exec() throws Exception {
Debug.println("START: " + mfi);
        CountDownLatch cdl = new CountDownLatch(1);
        MetaEventListener mel = meta -> {
Debug.println("META: " + meta.getType());
            if (meta.getType() == 47) cdl.countDown();
        };
        Sequence sequence = MfiSystem.getSequence(Path.of(mfi).toFile());
        volume(receiver, volume);
        sequencer.setSequence(sequence);
        sequencer.addMetaEventListener(mel);
        sequencer.start();
        long playMillis = Long.getLong("vavi.sound.test.playMillis", 0);
        if (playMillis > 0) {
            cdl.await(playMillis, TimeUnit.MILLISECONDS);
            sequencer.stop();
        } else {
            cdl.await();
        }
Debug.println("END: " + mfi);
        sequencer.removeMetaEventListener(mel);
    }

    /**
     * @param args mfi files ...
     */
    public static void main(String[] args) throws Exception {
        // -Dfaith4dll enables Faith Type 4. An empty value selects the
        // standard RTPlayer installation path; a non-empty value overrides it.
        String faith4DllProperty = System.getProperty("faith4dll");
        if (faith4DllProperty != null) {
            Path dll = faith4DllProperty.isBlank() ? FaithType4Renderer.defaultDll() : Path.of(faith4DllProperty);
            long playMillis = Long.getLong("vavi.sound.test.playMillis", 0);
            for (String arg : args) {
                FaithType4Renderer.renderAndPlay(Path.of(arg), dll, playMillis);
            }
            return;
        }
        PlayMFi app = new PlayMFi();
        app.setup();
        for (String arg : args) {
            app.mfi = arg;
            app.exec();
        }
        app.tearDown();
    }
}
