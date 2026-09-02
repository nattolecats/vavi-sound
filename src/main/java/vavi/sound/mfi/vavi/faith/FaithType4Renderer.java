/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 */

package vavi.sound.mfi.vavi.faith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.SysexMessage;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

import vavi.sound.mfi.MfiSystem;


/** Renders the MIDI portion of an MFi file with Faith's 32-bit Type 4 DLL. */
public final class FaithType4Renderer {

    private static final int SAMPLE_RATE = 44_100;
    private static final int DEFAULT_TEMPO_MICROS = 500_000;
    private static final Path DEFAULT_DLL = Path.of("C:\\Program Files (x86)\\Faith\\Ring Tone Authoring Tool\\Tools\\rt_synth_4.dll");
    /**
     * Keep the first ADPCM event on the same scheduler origin as Faith's MIDI
     * render.  A negative value here intentionally adds delay; zero leaves only
     * the small (10 ms by default) audio-line startup buffer.
     */
    private static final String FAITH_ADPCM_LATENCY = "0";

    private FaithType4Renderer() {
    }

    /** Default RTPlayer Type 4 DLL installed by Faith's authoring tool. */
    public static Path defaultDll() {
        return DEFAULT_DLL;
    }

    /**
     * Renders and plays an MFi file through an explicitly supplied
     * {@code rt_synth_4.dll}. The DLL is loaded by an x86 Java child process;
     * therefore this also works from a normal 64-bit Java runtime.
     *
     * @param mld MFi/MLD input file
     * @param dll Faith {@code rt_synth_4.dll}
     * @param maximumPlaybackMillis zero for the complete MIDI sequence
     */
    public static void renderAndPlay(Path mld, Path dll, long maximumPlaybackMillis) throws Exception {
        if (!Files.isRegularFile(mld)) throw new IllegalArgumentException("MFi file not found: " + mld);
        if (!Files.isRegularFile(dll)) throw new IllegalArgumentException("Faith Type4 DLL not found: " + dll);

        Path java = findX86Java();

        Path temporaryDirectory = Files.createTempDirectory("vavi-faith-type4-");
        try {
            Path events = temporaryDirectory.resolve("events.tsv");
            Path output = temporaryDirectory.resolve("faith-type4.wav");
            javax.sound.midi.Sequence sequence = MfiSystem.toMidiSequence(MfiSystem.getSequence(mld.toFile()));
            long lastFrame = writeEvents(sequence, events, maximumPlaybackMillis);
            if (lastFrame == 0) throw new IllegalArgumentException("no MIDI channel events in: " + mld);

            Process process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                                                 "vavi.sound.mfi.vavi.faith.FaithType4RendererProcess",
                                                 dll.toAbsolutePath().toString(), events.toString(), output.toString())
                    .redirectErrorStream(true)
                    .start();
            String processOutput = readAll(process.getInputStream());
            int exit = process.waitFor();
            if (exit != 0 || !Files.isRegularFile(output)) {
                throw new IOException("Faith Type4 renderer failed (exit " + exit + "):\n" + processOutput);
            }
            System.err.print(processOutput);
            // Faith renders the MIDI stream only.  Keep the MFi sequencer running
            // in parallel, but forward only its special SysEx messages: these load
            // and play ADPCM without duplicating the ordinary MIDI with Gervill.
            try (AdpcmPlayback adpcm = startAdpcmPlayback(mld)) {
                play(output, maximumPlaybackMillis);
            }
        } finally {
            deleteTemporaryFiles(temporaryDirectory);
        }
    }

    /** Writes {@code frame, status, data1, data2} records for the x86 renderer. */
    static long writeEvents(javax.sound.midi.Sequence sequence, Path output, long maximumPlaybackMillis) throws IOException {
        List<MidiEvent> events = new ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) events.add(track.get(i));
        }
        // Release voices before allocating new ones at the same timestamp.
        // Faith's DLL has a fixed voice pool; sending NoteOn first can cause
        // avoidable voice stealing even when the musical polyphony fits.
        events.sort(Comparator.comparingLong(MidiEvent::getTick)
                             .thenComparingInt(event -> {
                                 if (!(event.getMessage() instanceof ShortMessage message)) return 1;
                                 int command = message.getCommand();
                                 return command == ShortMessage.NOTE_OFF ||
                                        (command == ShortMessage.NOTE_ON && message.getData2() == 0) ? 0 : 1;
                             }));

        List<Tempo> tempos = new ArrayList<>();
        for (MidiEvent event : events) {
            if (event.getMessage() instanceof MetaMessage meta && meta.getType() == 0x51 && meta.getData().length == 3) {
                byte[] data = meta.getData();
                tempos.add(new Tempo(event.getTick(), ((data[0] & 0xff) << 16) | ((data[1] & 0xff) << 8) | (data[2] & 0xff)));
            }
        }
        TempoMap tempoMap = new TempoMap(sequence.getResolution(), tempos);
        long maximumFrame = maximumPlaybackMillis > 0 ? maximumPlaybackMillis * SAMPLE_RATE / 1000 : Long.MAX_VALUE;
        List<String> lines = new ArrayList<>();
        long lastFrame = 0;
        for (MidiEvent event : events) {
            if (!(event.getMessage() instanceof ShortMessage message)) continue;
            int command = message.getCommand();
            if (command < ShortMessage.NOTE_OFF || command > ShortMessage.PITCH_BEND) continue;
            long frame = Math.round(tempoMap.microsAt(event.getTick()) * SAMPLE_RATE / 1_000_000d);
            if (frame > maximumFrame) continue;
            lines.add(frame + "\t" + message.getStatus() + "\t" + message.getData1() + "\t" + message.getData2());
            lastFrame = Math.max(lastFrame, frame);
        }
        Files.write(output, lines);
        return lastFrame;
    }

    /** Locates the x86 JVM which can load Faith's 32-bit DLL. */
    static Path findX86Java() {
        String configured = System.getProperty("faithjava", "");
        if (!configured.isBlank()) return requireJava(Path.of(configured));

        String javaHomeX86 = System.getenv("JAVA_HOME_X86");
        if (javaHomeX86 != null && !javaHomeX86.isBlank()) {
            return requireJava(Path.of(javaHomeX86, "bin", "java.exe"));
        }
        if ("32".equals(System.getProperty("sun.arch.data.model"))) {
            return requireJava(Path.of(System.getProperty("java.home"), "bin", "java.exe"));
        }

        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        if (programFilesX86 != null) {
            for (String vendor : List.of("Java", "Eclipse Adoptium", "BellSoft")) {
                Path directory = Path.of(programFilesX86, vendor);
                if (!Files.isDirectory(directory)) continue;
                try (var homes = Files.list(directory)) {
                    Path found = homes.map(home -> home.resolve("bin").resolve("java.exe"))
                                      .filter(Files::isRegularFile)
                                      .sorted(Comparator.reverseOrder())
                                      .findFirst().orElse(null);
                    if (found != null) return found;
                } catch (IOException ignored) {
                }
            }
        }
        throw new IllegalStateException("32-bit Java is required for Faith Type4; set -Dfaithjava=<path-to-x86-java.exe> or JAVA_HOME_X86");
    }

    private static Path requireJava(Path java) {
        if (!Files.isRegularFile(java)) throw new IllegalStateException("32-bit Java executable not found: " + java);
        return java.toAbsolutePath();
    }

    /** Starts only the MFi audio/SysEx side of a sequence. */
    private static AdpcmPlayback startAdpcmPlayback(Path mld) throws Exception {
        String previousLatency = System.getProperty("vavi.sound.mobile.AudioEngine.latency");
        // Keep the override scoped to Faith playback.  With Faith's rendered
        // MIDI stream having no synthesizer lead, zero makes ADPCM start at the
        // first dispatched Note On rather than adding an artificial delay.
        System.setProperty("vavi.sound.mobile.AudioEngine.latency", FAITH_ADPCM_LATENCY);
        vavi.sound.mfi.Sequencer sequencer = MfiSystem.getSequencer();
        vavi.sound.mfi.Synthesizer synthesizer = MfiSystem.getSynthesizer();
        try {
            sequencer.open();
            synthesizer.open();
            Receiver receiver = synthesizer.getReceiver();
            sequencer.getTransmitter().setReceiver(new Receiver() {
                @Override
                public void send(MidiMessage message, long timeStamp) {
                    if (message instanceof SysexMessage) {
                        receiver.send(message, timeStamp);
                    }
                }

                @Override
                public void close() {
                    receiver.close();
                }
            });
            sequencer.setSequence(MfiSystem.getSequence(mld.toFile()));
            sequencer.start();
            return new AdpcmPlayback(sequencer, synthesizer, previousLatency);
        } catch (Exception | Error e) {
            try { sequencer.close(); } catch (Exception ignored) { }
            try { synthesizer.close(); } catch (Exception ignored) { }
            restoreLatency(previousLatency);
            throw e;
        }
    }

    /** Owns the otherwise muted sequencer used to dispatch ADPCM events. */
    private record AdpcmPlayback(vavi.sound.mfi.Sequencer sequencer,
                                 vavi.sound.mfi.Synthesizer synthesizer,
                                 String previousLatency) implements AutoCloseable {
        @Override
        public void close() {
            try {
                sequencer.stop();
                sequencer.close();
                synthesizer.close();
            } finally {
                restoreLatency(previousLatency);
            }
        }
    }

    private static void restoreLatency(String previousLatency) {
        if (previousLatency == null) {
            System.clearProperty("vavi.sound.mobile.AudioEngine.latency");
        } else {
            System.setProperty("vavi.sound.mobile.AudioEngine.latency", previousLatency);
        }
    }

    private static void play(Path file, long maximumPlaybackMillis) throws Exception {
        try (AudioInputStream input = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat format = input.getFormat();
            try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                line.open(format);
                line.start();
                byte[] buffer = new byte[8192];
                long remaining = maximumPlaybackMillis > 0 ? Math.round(maximumPlaybackMillis * format.getFrameRate() / 1000d) : -1;
                while (remaining != 0) {
                    int requested = buffer.length;
                    if (remaining > 0) {
                        long remainingBytes = remaining > Long.MAX_VALUE / format.getFrameSize() ? Long.MAX_VALUE : remaining * format.getFrameSize();
                        requested = (int) Math.min(buffer.length, remainingBytes);
                    }
                    int bytes = input.read(buffer, 0, requested);
                    if (bytes < 0) break;
                    line.write(buffer, 0, bytes);
                    if (remaining > 0) remaining -= bytes / format.getFrameSize();
                }
                line.drain();
            }
        }
    }

    private static String readAll(java.io.InputStream input) throws IOException {
        return new String(input.readAllBytes());
    }

    private static void deleteTemporaryFiles(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private record Tempo(long tick, int microsPerQuarter) {
    }

    /** Converts PPQ ticks to time without requiring a real-time Java sequencer. */
    private static final class TempoMap {
        private final int resolution;
        private final List<Tempo> tempos;

        TempoMap(int resolution, List<Tempo> tempos) {
            this.resolution = resolution;
            this.tempos = tempos;
        }

        long microsAt(long tick) {
            long previousTick = 0;
            long micros = 0;
            int tempo = DEFAULT_TEMPO_MICROS;
            for (Tempo change : tempos) {
                if (change.tick > tick) break;
                micros += (change.tick - previousTick) * (long) tempo / resolution;
                previousTick = change.tick;
                tempo = change.microsPerQuarter;
            }
            return micros + (tick - previousTick) * (long) tempo / resolution;
        }
    }
}
