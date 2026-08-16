# vavi.sound.adpcm.sshd

♪ Provides SONY "Audio Stream" (SShd, a.k.a. ADS) related classes.

Yet another sony adpcm container: a `SShd` header plus a `SSbd` body of PCM16LE,
PS-ADPCM or DVI/IMA ADPCM, laid out in interleave blocks.
`.ss2` (demuxed videos, e.g. Mobile Suit Gundam: Journey to Jaburo (PS2)) is one of
the many extensions it is found with, `.ads` being the official one.

### Status

completed, decoding is byte identical to vgmstream for the samples at hand

## References

 * https://github.com/vgmstream/vgmstream/blob/master/src/meta/sshd.c
 * https://github.com/vgmstream/vgmstream/blob/master/src/coding/ima_decoder.c
 * https://github.com/vgmstream/vgmstream/blob/master/doc/FORMATS.md

### Tech-know

 * devs hacked this format a lot, so the start offset, the usable body size and the
   loop points are all detected heuristically, exactly as vgmstream does. Those
   heuristics need the size of the whole file, and some of them random access to it.
 * the last interleave block set of a stream may be incomplete, in which case the
   trailing channels are decoded from what follows the usable body (padding frames),
   again as vgmstream does.
 * PS-ADPCM is decoded by [vavi.sound.adpcm.psx](../psx).

## TODO

 * loop playback (the loop points are only reported, as properties of the `AudioFormat`)
