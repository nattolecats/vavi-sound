[![Release](https://jitpack.io/v/umjammer/vavi-sound.svg)](https://jitpack.io/#umjammer/vavi-sound)
[![Java CI](https://github.com/umjammer/vavi-sound/actions/workflows/maven.yml/badge.svg)](https://github.com/umjammer/vavi-sound/actionsworkflows/maven.yml)
[![CodeQL](https://github.com/umjammer/vavi-sound/actions/workflows/codeql-analysis.yml/badge.svg)](https://github.com/umjammer/vavi-sound/actions/workflows/codeql-analysis.yml)
![Java](https://img.shields.io/badge/Java-25-b07219)

# vavi-sound

<img alt="logo" src="src/test/resources/duke_cellphone.png" width="160" />

Provides old school Japanese cell phone sounds library as `javax.sound(.midi)` SPI<br/>
includes many ADPCM codecs and the [SSRC](https://github.com/shibatch/SSRC) sampling rate converter.

### Status

| **SPI** | **Codec**                                                               | **Description**           | **IN Status** | **OUT Status** |  **SPI Status**  | **Comment**                                 |
|:--------|:------------------------------------------------------------------------|:--------------------------|:-------------:|:--------------:|:----------------:|:--------------------------------------------|
| midi    | [MFi](src/main/java/vavi/sound/midi/mfi)                                | Japanese ring tone format |      🚧       |       ✅        |        ✅         | DoCoMo                                      |
| midi    | [SMAF](src/main/java/vavi/sound/midi/smaf)                              | YAMAHA ring tone format   |      🚧       |       ✅        |        ✅         | au, Softbank                                |
| sampled | [MFi](src/main/java/vavi/sound/sampled/mfi)                             | Japanese ring tone format |       ✅       |       ✅        |        ✅         | DoCoMo                                      |
| sampled | [SMAF](src/main/java/vavi/sound/sampled/smaf)                           | YAMAHA ring tone format   |       ✅       |       ✅        |        ✅         | au, Softbank                                |
| sampled | [CCITT ADPCM](src/main/java/vavi/sound/adpcm/ccitt)                     | G711, G721, G723          |       ✅       |       ✅        |        ✅         | G721 cellphone w/ Fuetrek chip              |
| sampled | [DVI ADPCM](src/main/java/vavi/sound/adpcm/dvi)                         | DVI ADPCM                 |       ✅       |       ✅        |        ✅         | same as IMA                                 |
| sampled | [IMA ADPCM](src/main/java/vavi/sound/adpcm/ima)                         | IMA ADPCM                 |       ✅       |       ✅        | ✅ <sup>[2]</sup> |                                             |
| sampled | [MA ADPCM](https://gitlab.com/umjammer/vavi-sound-nda) <sup>[1]</sup>   | YAMAHA ADPCM-MA           |       ✅       |       ✅        |        ✅         | cellphone w/ YAMAHA MA chip, YMU762, YMU765 |
| sampled | [MS ADPCM](src/main/java/vavi/sound/adpcm/ms)                           | Microsoft ADPCM           |       ✅       |       ✅        | ✅ <sup>[2]</sup> |                                             |
| sampled | [OKI ADPCM](src/main/java/vavi/sound/adpcm/oki)                         | OKI MSM6258 ADPCM         |       ✅       |       ✅        | ✅ <sup>[2]</sup> | x68000                                      |
| sampled | [ROHM ADPCM](https://gitlab.com/umjammer/vavi-sound-nda) <sup>[1]</sup> | ROHM ADPCM                |       ✅       |       ✅        |        ✅         | cellphone w/ Rohm chip                      |
| sampled | [VOX ADPCM](src/main/java/vavi/sound/adpcm/vox)                         | Dialogic ADPCM (VOX)      |       ✅       |       ✅        | ✅ <sup>[2]</sup> | OKI MSM7580                                 |
| sampled | [YM2068 ADPCM](src/main/java/vavi/sound/adpcm/ym2608)                   | YAMAHA ADPCM-A            |       ✅       |       ✅        |        -         | YM2608 etc.                                 |
| sampled | [YAMAHA ADPCM](src/main/java/vavi/sound/adpcm/yamaha)                   | YAMAHA ADPCM-A            |       ✅       |       ✅        | ✅ <sup>[2]</sup> | same as YM2608 ADPCM                        |
| sampled | [PSX ADPCM](src/main/java/vavi/sound/adpcm/psx)                         | SONY ADPCM                |       ✅       |       -        |        ✅         | .mi\[bh], .mic                              |
| sampled | [SShd](src/main/java/vavi/sound/adpcm/sshd)                             | SONY Audio Stream (ADS)   |       ✅       |       -        |        ✅         | .ads, .ss2, psx/pcm16/ima                   |
| sampled | [ssrc](src/main/java/vavi/sound/pcm/resampling/ssrc)                    | resampling                |       ✅       |       -        |  ✅ <su>*</sup>   | [*] need to wait for phase 1                |

<sub>\[1] implemented in another library</sub><br/>
<sub>\[2] wav file readable</sub>

## Install

* https://jitpack.io/#umjammer/vavi-sound

## Usage

### system properties

- `vavi.sound.sampled.spi.ssrc` ... ssrc sampling rate conversion provider on/off, default `false`

#### MFi Type-2 ADPCM codec selection

For MFi Type-2 (`format=0x81`) ADPCM tracks, the codec can be selected per
audio-data number by appending the stream number to the JVM property name:

```text
-Dvavi.sound.mobile.FuetrekAudioEngine.g723Decoder.5=g723
```

The 2-bit selector `g723Decoder.*` accepts `auto`, `g723`, `g721`, `ima2`, and
`ima` (`ima` is an alias for `ima2`).  The G.723 bit packing can be selected
with `vavi.sound.mobile.FuetrekAudioEngine.g723BitOrder.*` as `little`/`le` or
`big`/`be`.  For 4-bit streams, `vavi.sound.mobile.FuetrekAudioEngine.decoder`
accepts `g721`, `yamaha`, `ma`, `dvi`, `oki`, `rohm`, and `vox`.

The MFi header does not identify the codec beyond the sample bit depth.  With
`g723Decoder.*=auto`, different streams may therefore be assigned different
codecs by the roughness heuristic, and a file can still play incorrectly.
Use explicit per-stream values when reproducing a known handset/resource.

#### Faith RTPlayer Type 4 playback (Windows)

Specify `-Dfaith4dll` to play an MLD through RTPlayer's Type 4 sound source,
which was widely used in NTT DoCoMo mobile phones. When the property is given
without a value, the standard installation is used:

```text
C:\Program Files (x86)\Faith\Ring Tone Authoring Tool\Tools\rt_synth_4.dll
```

The DLL is called natively from a 32-bit JVM helper. The sound source is not
included in this source code for license-compliance reasons; Faith's authoring
tool must be appropriately installed on the system before using this option.
An alternate Type 4-compatible DLL can be supplied as
`-Dfaith4dll=C:\path\to\rt_synth_4.dll`.

### sample

 * MFi (.mld) ... [PlayMFi](src/test/java/PlayMFi.java)
 * SMAF (.mmf) ... [PlaySMAF](src/test/java/PlaySMAF.java)

### FAQ

#### Q. can I use SSRC sampling converter under LGPL license?

A. yes you can, follow those steps

* create a separated jar (ssrc.jar) file including ssrc classes. (**never include those .class files into your application jar file**)
    * `vavi/sound/pcm/resampling/ssrc/SSRC.class`
    * `vavi/util/SplitRadixFft.class`
    * `vavi/util/I0Bessel.class`
* ⚠️ **caution**:
    * your application complies with the LGPL. customers **have a right to reverse engineering your application**.
    * if you bundle ssrc.jar with a distribution, you **must offer a way to get ssrc source code**.
* see also
    * https://opensource.org/licenses/LGPL-2.1
    * http://www.gnu.org/licenses/lgpl-java.en.html

### Tech Know

* \[github actions] workflow on ubuntu java8 cannot deal line `PCM_SIGNED 8000.0 Hz, 16 bit, mono, 2 bytes/frame, little-endian`
* \[midi volume] avoiding noise, `SoundUtil#volume` should be called before `Sequencer#setSequence`

## References

 * https://github.com/shibatch/SSRC
 * adpcm
   * https://github.com/SatyrDiamond/adpcm
   * https://segaretro.org/Yamaha_Super_Intelligent_Sound_Processor (yamaha aica adpcm)
   * https://github.com/A-SunsetMkt-Forks/vgmstream/blob/master/src/coding (ps2 etc.)
   * psx
     * https://archive.org/details/adpcmplayerv1.44h (ps2 player)
     * http://zarala.g2.xrea.com/soft/analyse/ps2_adpcm.txt
 * Beatnik RMF https://github.com/heyigor/miniBAE
 * FITOM https://github.com/madscient/FITOMApp (midi to opl series)
 * [RTTTL (Ringing Tones text transfer language)](https://web.archive.org/web/20070704033948/http://www.convertyourtone.com/rtttl.html)
 * smaf
   * https://murachue.sytes.net/web/softlist.cgi?mode=desc&title=mmftool
   * https://github.com/mmontag/mmfplay (w/ ym chips)
   * https://funyamora.hatenadiary.org/entry/20080225/1203889006 🇯🇵 (.spf SMAF/Phase)
   * https://github.com/logue/smfplayer.js
   * https://github.com/shirajira/OpenMF
   * https://github.com/Pusungwi/mmf_parser
 * mfi
   * https://github.com/starg2/timidity41/blob/dev41/timidity/mfi.c
   * https://github.com/logue/smfplayer.js/blob/master/src/mld.js
   * https://github.com/SquirrelJME/SquirrelJME/tree/trunk/modules/vendor-api-keitaiwiki-music/src/main/java/com/keitaiwiki/music
   * https://github.com/GrenderG/openDoJa (futrek)
   * https://github.com/TASEmulators/freej2me-plus/blob/devel/src/javax/microedition/media/decoders/MLDDecoder.java

## TODO

  * ~~use `Receiver` and sysex instead of `MetaEventListener`~~
  * ssrc: use nio pipe for 1st pass
    * on macos m2 ultra 1st pass is in a blink of an eye
  * ~~`ima`, `ms` adpcm: wav reader~~
    * ~~`tritonus:tritonus-remaining:org.tritonus.sampled.file.WaveAudioFileReader`~~
  * ~~use service provider for mfi, smaf sequencer~~
  * ~~service loader instead of vavi.properties~~
  * midi -> smaf
  * ~~https://github.com/but80/smaf825 (patch dump)~~ -> https://github.com/umjammer/vavi-sound-ma
  * ~~psx adpcm spi~~
  * ~~sshd (.ss2) spi~~
  * ~~adpcm playback timing precisely~~

---

<sub>image designed by @umjammer, drawn by nano banana</sub>
