# Handing PCM to libsound

Written for the other side of the seam: a decoder that has frames and wants
them heard. skinema is the one this was written against, and nothing in it is
particular to skinema.

The whole of it is `AudioSink`, whose own documentation states each rule and the
reason it exists. This page is the same rules read in the order a decoder meets
them, plus the three questions people ask first.

## The shape

Interleaved PCM, little-endian, one sample per channel per frame:

```kotlin
AudioFormat(sampleRate = 48_000, channels = 2, encoding = PcmEncoding.S16LE)
```

`S16LE` is what every backend must accept. `F32LE` exists because PipeWire and
CoreAudio are float-native and can pass it through without converting, and a
backend that cannot is free to refuse it, which `open` reports by throwing
rather than by playing something wrong. A decoder with no reason to prefer one
sends S16LE.

`AudioFormat` carries the frame arithmetic, and it is worth using rather than
repeating: `bytesPerFrame`, `framesIn`, `bytesFor`, `nanosFor`, `framesFor`. The
duration conversions split into whole seconds plus a remainder, because
`frames * 1_000_000_000` leaves Long's range after about fifty-three hours at
48 kHz, which a long-running process reaches and a unit test does not.

## Nobody here resamples, so do not resample for us

Open the sink at the rate the media actually is. The sound server converts to
whatever the device wants, and it is better placed to do it than either of us.

This is measured rather than assumed, in all three backends: the PulseAudio one
puts the rate you gave into the sample spec and the server meets it, the WASAPI
sink sets `AUTOCONVERTPCM` because the engine otherwise refuses any format but
its own mix format, and the CoreAudio output unit is told the rate in its stream
description.

So a 44.1 kHz file on a 48 kHz graph is not your problem. Decoding it to 48 kHz
yourself means two conversions where one would do, and the second one is the
server's whether you like it or not.

## The write is the clock

```kotlin
sink.write(pcm, 0, pcm.size)
```

It returns when the device has taken every byte, not when they were queued. That
is the entire pacing mechanism: a decode loop that writes needs no timer, no
sleep and no frame budget, because the write returning is what tells it that
time has passed.

Two consequences worth stating plainly.

A decode loop should write and let it block. A loop that keeps a large buffer of
its own ahead of the sink turns this into a busy loop and makes a stall watchdog
fire during healthy playback.

`length` must be a whole number of frames. A partial frame is a shifted stream
from that point on, and nothing downstream can detect it.

If your decoder is shaped around a callback rather than a push loop, `PullPump`
drives a `PcmSource` into a sink and gives you the same pacing from the other
side. If you need a buffer between two threads, `PcmRingBuffer` is the bridge,
and it keeps the two failure directions apart: a device callback cannot wait, so
a read short of data fills the shortfall with silence and counts an underrun,
while a producer can wait and does.

## Reading the playhead

```kotlin
sink.framePosition()   // frames the device has played since open
sink.latencyNanos()    // how far ahead of the speaker the write head is
```

`framePosition` counts frames the device has actually played, not frames you
have written. `open` resets it to zero, `stop` freezes it, `start` resumes it.

`latencyNanos` is the whole path: what is queued in the client, plus the
server's share, plus the device's own. Do not add your own estimate of the
server's part on top. A pacer that did would get it wrong differently on every
machine, which is the reason this number is specified the way it is.

Where a video frame will be heard is therefore `framePosition` plus
`latencyNanos`, converted through `AudioFormat`. That arithmetic being in every
consumer is a known wart, and section 9.4 of the plan is the call that removes
it.

## Seeking

Order matters, and it is the one sequence that looks arbitrary and is not:

```kotlin
sink.stop()      // freeze the device first
sink.flush()     // then drop what it has not played
val anchor = sink.framePosition()
```

Reading the position before stopping samples a value the still-draining buffer
is about to move past, and re-anchoring a clock backwards is the one transition
a video pacer cannot absorb.

`framePosition` need not be monotonic across a flush. Some backends reconcile
their counters around one. Carry the monotonic clamp above the sink rather than
asking the sink to invent numbers, because a fabricated position is worse than a
visibly jumpy one.

## Changing format mid-stream

Call `open` again. It replaces the stream, drops the previous buffered tail, and
restarts the frame position at zero. A track at another sample rate is exactly
this, and the position restarting is what a re-anchoring clock depends on.

## What not to do

- Do not resample to the device rate. See above.
- Do not buffer far ahead of the sink to keep writes fast. The blocking is the
  feature.
- Do not compute your own device latency. `latencyNanos` is the whole path.
- Do not treat a short write as possible. There is no such thing here: the call
  takes everything or throws.
- Do not close a sink from the thread that is writing to it and expect the write
  to have finished. `close` unblocks a write in flight on purpose, so a watchdog
  can free a thread parked against a device that has gone.

## If you want to change the samples

Do not put it in the decoder and do not ask for it here. Processing is a sink
that wraps a sink, in `libsound-dsp`, which depends on the contracts alone:

```kotlin
val sink = GainSink(LimiterSink(deviceSink), gain = 1.5f)
```

A visualiser is the same shape and is the cheapest of them: `TapSink` passes
every frame through unchanged and shows it to a callback on the way, so a level
meter or a spectrum reads the samples you were already writing rather than
opening a second path to the audio.

Anything you write yourself that wraps a sink owes four rules, and
`AudioSinkDecoratorContract` in libsound-core's test fixtures asserts them.
Extend it. The rule that costs the most when broken is the first one: a
decorator may buffer internally, but it may not return before the frames it
produced have been taken by the sink it wraps.

## What is not settled yet

Three things a decoder may notice, all of them named in the plan rather than
left to be discovered.

**Beyond stereo, channel order is a convention.** `AudioFormat` counts channels
and does not name them, so a 5.1 stream is interleaved in whatever order both
sides assume. Section 9.2 is the channel map that fixes it.

**`S24LE` and `S32LE` do not exist.** `PcmEncoding` carries two. Section 9.3
adds the others, which is what a capture path at higher bit depth produces.

**Presentation time is arithmetic you do yourself.** Section 9.4 makes it one
call.
