/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 */

package vavi.sound.mfi.vavi.faith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;


/** Isolated Java 8/x86 process which calls the Faith Type4 native API. */
public final class FaithType4RendererProcess {

    private static final int SAMPLE_RATE = 44_100;
    private static final int FRAMES_PER_BLOCK = 128;
    private static final int TAIL_MILLISECONDS = 1_500;
    private static final double OUTPUT_GAIN = 3.0;

    private FaithType4RendererProcess() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("usage: <dll> <events.tsv> <output.wav>");
        if (Native.POINTER_SIZE != 4) {
            throw new IllegalStateException("Faith Type4 requires a 32-bit JVM; this JVM is " + (Native.POINTER_SIZE * 8) + "-bit");
        }
        render(new File(args[0]), new File(args[1]), new File(args[2]));
    }

    private static void render(File dll, File eventFile, File output) throws Exception {
        List<Event> events = readEvents(eventFile);
        if (events.isEmpty()) throw new IllegalArgumentException("no MIDI events");

        NativeLibrary library = NativeLibrary.getInstance(dll.getAbsolutePath());
        Function openSynth = library.getFunction("RTPSynthOpen", Function.C_CONVENTION);
        Function closeSynth = library.getFunction("RTPSynthClose", Function.C_CONVENTION);
        IntByReference mode = new IntByReference(0);
        Pointer wrapper = openSynth.invokePointer(new Object[] { mode });
        if (wrapper == null) throw new IllegalStateException("RTPSynthOpen failed");
        try {
            Pointer api = wrapper.getPointer(0);
            Function renderBlock = method(api, 0x10);
            Function openChannel = method(api, 0x18);
            Function sendEvents = method(api, 0x20);
            int slot = openChannel.invokeInt(new Object[] { api, 0 });
            if (slot < 0) throw new IllegalStateException("OpenChannel(0) returned " + slot);
            writeWave(events, output, api, slot, renderBlock, sendEvents);
        } finally {
            closeSynth.invokeVoid(new Object[] { wrapper });
            library.close();
        }
    }

    private static Function method(Pointer api, long offset) {
        Pointer address = api.getPointer(offset);
        if (address == null) throw new IllegalStateException("missing API entry at 0x" + Long.toHexString(offset));
        return Function.getFunction(address, Function.C_CONVENTION);
    }

    private static void writeWave(List<Event> events, File output, Pointer api, int slot,
                                  Function renderBlock, Function sendEvents) throws Exception {
        long finalFrame = events.get(events.size() - 1).frame + (long) TAIL_MILLISECONDS * SAMPLE_RATE / 1_000;
        Memory block = new Memory(FRAMES_PER_BLOCK * 2L * Integer.BYTES);
        FileOutputStream stream = new FileOutputStream(output);
        try {
            writeWaveHeader(stream);
            long renderedFrames = 0;
            int eventIndex = 0;
            while (renderedFrames < finalFrame) {
                while (eventIndex < events.size() && events.get(eventIndex).frame <= renderedFrames) {
                    send(sendEvents, api, slot, events.get(eventIndex++));
                }
                block.clear();
                int result = renderBlock.invokeInt(new Object[] { api, block });
                if (result != 0) throw new IllegalStateException("RenderBlock returned " + result);
                for (int i = 0; i < FRAMES_PER_BLOCK; i++) {
                    writeLe16(stream, toPcm16(block.getInt(i * 8L)));
                    writeLe16(stream, toPcm16(block.getInt(i * 8L + 4)));
                }
                renderedFrames += FRAMES_PER_BLOCK;
            }
            stream.close();
            patchWaveSizes(output, renderedFrames * 4);
            System.out.println("Faith Type4: events=" + events.size() + " frames=" + renderedFrames + " output=" + output);
        } finally {
            try { stream.close(); } catch (Exception ignored) { }
        }
    }

    private static void send(Function function, Pointer api, int slot, Event event) {
        Memory data = new Memory(4);
        data.setByte(0, (byte) event.status);
        data.setByte(1, (byte) event.data1);
        data.setByte(2, (byte) event.data2);
        data.setByte(3, (byte) 0);
        int result = function.invokeInt(new Object[] { api, slot, data, 1 });
        if (result != 0) throw new IllegalStateException("SendEvents returned " + result);
    }

    private static short toPcm16(int value) {
        double scaled = value * OUTPUT_GAIN;
        if (scaled > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (scaled < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) Math.round(scaled);
    }

    private static List<Event> readEvents(File file) throws Exception {
        List<Event> result = new ArrayList<Event>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] fields = line.split("\\t", -1);
                if (fields.length != 4) throw new IllegalArgumentException("invalid event: " + line);
                result.add(new Event(Long.parseLong(fields[0]), Integer.parseInt(fields[1]),
                                     Integer.parseInt(fields[2]), Integer.parseInt(fields[3])));
            }
        } finally {
            reader.close();
        }
        Collections.sort(result, new Comparator<Event>() {
            public int compare(Event left, Event right) {
                return Long.compare(left.frame, right.frame);
            }
        });
        return result;
    }

    private static void writeWaveHeader(FileOutputStream output) throws Exception {
        output.write(new byte[] { 'R', 'I', 'F', 'F' }); writeLe32(output, 0);
        output.write(new byte[] { 'W', 'A', 'V', 'E', 'f', 'm', 't', ' ' });
        writeLe32(output, 16); writeLe16(output, 1); writeLe16(output, 2);
        writeLe32(output, SAMPLE_RATE); writeLe32(output, SAMPLE_RATE * 4);
        writeLe16(output, 4); writeLe16(output, 16);
        output.write(new byte[] { 'd', 'a', 't', 'a' }); writeLe32(output, 0);
    }

    private static void patchWaveSizes(File output, long dataBytes) throws Exception {
        if (dataBytes > Integer.MAX_VALUE - 36) throw new IllegalArgumentException("rendered WAV is too large");
        RandomAccessFile file = new RandomAccessFile(output, "rw");
        try {
            file.seek(4); writeLe32(file, (int) (36 + dataBytes));
            file.seek(40); writeLe32(file, (int) dataBytes);
        } finally {
            file.close();
        }
    }

    private static void writeLe16(java.io.OutputStream output, int value) throws Exception {
        output.write(value & 0xff); output.write((value >>> 8) & 0xff);
    }

    private static void writeLe32(java.io.OutputStream output, int value) throws Exception {
        output.write(value & 0xff); output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff); output.write((value >>> 24) & 0xff);
    }

    private static void writeLe32(RandomAccessFile output, int value) throws Exception {
        output.write(value & 0xff); output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff); output.write((value >>> 24) & 0xff);
    }

    private static final class Event {
        final long frame;
        final int status;
        final int data1;
        final int data2;

        Event(long frame, int status, int data1, int data2) {
            this.frame = frame;
            this.status = status;
            this.data1 = data1;
            this.data2 = data2;
        }
    }
}
