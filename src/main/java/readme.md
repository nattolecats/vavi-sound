# vavi.sound

those are sound API specs for the japanese old school cellphone ringtones.

## Feature

 * we can play MFi/SMAF as a midi file because it's implemented as `javax.sound.midi.spi`
 * supports until MFi version 3.1
 * mutual conversion between MFi ←→ SMF is possible
 * by adding a handler for model-dependent data, you can create voice calls that are compatible with each model.

## Abstract

`vavi.sound` consists of three parts.

 1. ADPCM codecs
 2. MFi library
 3. `javax.sound.midi.spi` implementation

### 1. ADPCM codecs

packages

 * `vavi.sound.adpcm.ccitt`
 * `vavi.sound.adpcm.ma`
 * `vavi.sound.adpcm.vox`

A group of packages that handle the ADPCM codec used for voice ringtone.
Because it is implemented as Java standard IO ({@link java.io.InputStream}/{@link java.io.OutputStream})
Easy to handle and stream easily.

### 2. MFi library

packages

 * `vavi.sound.mfi`
 * `vavi.sound.mfi.vavi`
 *   :

This is the core part of the ringtone library. It can handle MFi file structures and convert them to MIDI structures.
It is also possible to convert from MIDI to MFi. It basically has the same structure as the {@link javax.sound.midi} package.
{@link vavi.sound.mfi.vavi.mitsubishi Company D} as a package that handles model-dependent data
{@link vavi.sound.mfi.vavi.nec Company N} has been implemented as a sample.

### 3. `javax.sound.midi.spi` implementation

packages

 * `vavi.sound.midi`
 * `vavi.sound.midi.mfi`

This is a package to function as an implementation of `javax.sound.midi.spi`.
[SPI](http://java.sun.com/j2se/1.5.0/ja/docs/ja/guide/sound/programmer_guide/chapter1.html#111901)
If you register according to the specifications, you can use MFi files as a midi file format.
It will be possible to play it.

## How to

### Faith Type4 DLL playback (Windows)

The test-inclusive command-line JAR can render an MLD through the original
32-bit Faith Type4 synthesizer. Specify the DLL with the same JVM-property
style used for `-Dsf2`:

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-25.0.4.1"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -Dfaith4dll `
  -Dfaithjava="C:\Program Files (x86)\Java\jdk1.8.0_152\bin\java.exe" `
  -jar .\target\vavi-sound-1.0.28-jar-with-dependencies-and-tests.jar `
  "C:\mld\WALKURENRITT (DIE WALKURE).mld"
```

`-Dfaith4dll` enables playback with RTPlayer's Type 4 sound source, a sound
source widely used by NTT DoCoMo mobile phones. With no value, the renderer
uses `C:\Program Files (x86)\Faith\Ring Tone Authoring Tool\Tools\rt_synth_4.dll`.
The Type4 DLL is called natively with JNA in a 32-bit JVM child process and
the resulting 44.1 kHz WAV is played by the main JVM. This avoids loading a
32-bit DLL into a 64-bit JVM and has no PowerShell dependency. Set
`-Dfaithjava` to an x86 Java executable (Java 8 or newer), or set
`JAVA_HOME_X86`; common installations under `Program Files (x86)` are also
discovered automatically. `-Dvavi.sound.test.playMillis=5000` limits both the
rendered sequence and the audition to five seconds. MFi `adat` ADPCM is
started concurrently through the regular MFi audio engine. An alternate
Type 4-compatible DLL may be selected by assigning its path to
`-Dfaith4dll`. For license compliance, the sound source is not distributed
with this project; Faith's authoring tool must be installed separately before
using this option.

MFi Type-2's 2-bit header does not identify the codec.  The format-defined
G.723 decoder is used by default.  An experimental `auto` profile is
available for legacy resources that need statistical G.721 fallback; enable
it explicitly with `-Dvavi.sound.mobile.FuetrekAudioEngine.g723Decoder=auto`.
For diagnostics, `-Dvavi.sound.mobile.FuetrekAudioEngine.g723Decoder=ima2` or
`g723` forces one decoder globally.  A stream-specific override can be used
to isolate mixed resources, for example
`-Dvavi.sound.mobile.FuetrekAudioEngine.g723Decoder.3=ima2` and
`-Dvavi.sound.mobile.FuetrekAudioEngine.g723Decoder.4=g723`.

### 1. Create MIDI from MFi

```java
            File inFile = new File("sample.mld");
            vavi.sound.mfi.Sequence mfiSequence = MfiSystem.getSequence(inFile);
            Sequence midiSequence = MfiSystem.toMidiSequence(mfiSequence);

            File outFile = new File("sample.mid");
            MidiSystem.write(midiSequence, 0, outFile);
```

### 2. Create MFi from MIDI

```java
            File inFile = new File("sample.mid");
            javax.sound.midi.Sequence midiSequence = MidiSystem.getSequence(inFile);
            MidiFileFormat midiFileFormat = MidiSystem.getMidiFileFormat(inFile);
            int type = midiFileFormat.getType();
            vavi.sound.mfi.Sequence mfiSequence = MfiSystem.toMfiSequence(midiSequence, type);

            File outFile = new File("sample.mld");
            MfiSystem.write(mfiSequence, VaviMfiFileFormat.FILE_TYPE, outFile);
```

### 3. Create ring tone w/ voice

```java
            int time = ...;
            InputStream waveStream = ...;

            Sequence sequence = new Sequence();
            Track track = sequence.createTrack();
            MfiMessage message;

            message = new CuePointMessage(0x00, 0x00);
            track.add(new MfiEvent(message, 0l));

            double aDelta = (60d / 120d) / 120d * 1000;
            int delta = (int) Math.round(time / aDelta);

            message = new TempoMessage(0x00, 0xcb, 0x78);
            track.add(new MfiEvent(message, 0l));

            MitsubishiMessage mdmessage = new MitsubishiMessage();
            mdmessage.toVolume(0x3f);
            track.add(new MfiEvent(mdmessage, 0l));

            mdmessage = new MitsubishiMessage();
            mdmessage.toPan(0x20);
            track.add(new MfiEvent(mdmessage, 0l));

            mdmessage = new MitsubishiMessage();
            mdmessage.toVoice(waveStream, 8000, 4, false);
            track.add(new MfiEvent(mdmessage, 0l));

            for (int i = 0; i < delta / 256; i++) {
                message = new NopMessage(0xff, 0);
                track.add(new MfiEvent(message, 0l));
            }
            message = new NopMessage(delta % 256, 0);
            track.add(new MfiEvent(message, 0l));

            message = new EndOfTrackMessage(0, 0);
            track.add(new MfiEvent(message, 0l));

            MfiSystem.write(sequence, VaviMfiFileFormat.FILE_TYPE, new File("output.mld"));
```
