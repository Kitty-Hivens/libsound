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

`S16LE` is what every backend must accept, and there are four others. `U8` is
old WAV and what derives from it. `S32LE` is where 24-bit content arrives, in
the top 24 bits, because FFmpeg has no 24-bit sample format to send it in.
`F32LE` is float, which PipeWire and CoreAudio can pass through without
converting. `F64LE` is rare sources and some filter outputs. A decoder with no
reason to prefer one sends S16LE.

## Ask what it takes, do not find out

Backends do not accept the same set, and the differences are not small:
libpulse has no 64-bit float at all, and the JavaSound fallback takes whatever
the JVM's default line takes, which is three of the five.

```kotlin
if (sink.accepts(shape)) sink.open(shape)
sink.acceptedEncodings          // the rungs of the ladder, before you have frames
```

`accepts` is true exactly when `open` would not throw for want of the shape,
and the two are asserted against each other on every backend. So a ladder down
from what the media is towards the floor is a walk over `acceptedEncodings`
rather than a sequence of calls wrapped in `catch`, and it can be walked before
anything has been decoded.

`open` still throws, and it throws `AudioException` rather than an argument
exception, so a consumer that would rather try than ask can. What it must not
do is treat a refusal as a bug: it is the answer to a question, and the
question has a cheaper form.

## Beyond stereo, say what the channels are

`AudioFormat.layout` is what each channel is, and therefore the order they are
interleaved in:

```kotlin
AudioFormat(48_000, 6, PcmEncoding.S16LE, ChannelLayout.SURROUND_5_1)
```

It defaults to whatever FFmpeg means by that many channels, so a stream that
declared nothing lands where FFmpeg would have put it. Counting is not enough:
six channels is `5.1` or `5.1(side)`, they differ in whether the last pair is
the rear or the sides, and laying one out as the other moves a film's rear
channels into its side ones.

Ask `Capability.CHANNEL_PLACEMENT` before trusting it. Present, the backend
tells the device what each channel is and the layout is honoured exactly.
Absent, only the count goes across and the device applies its own convention,
which is a different rendering rather than a failure. Nothing to ask below three
channels, where every platform agrees.

A layout naming a position the backend cannot express is refused by `accepts`
rather than carried with a channel missing, and the refusal says what to do
instead: send the same audio with `ChannelLayout.unspecified(n)` and take the
platform's own ordering, which is what a stream that declared no layout gets
anyway.

`AudioFormat.significantBits` is the other half of describing a sample: how many
of the bits carry signal, as against how wide the container is. It matters in
exactly one place and that place is common, since 24-bit content arrives as
S32LE with 24 significant bits, and packing 24 real bits into 24 is free while
packing 32 into 24 is a quiet loss.

`AudioFormat` carries the frame arithmetic, and it is worth using rather than
repeating: `bytesPerFrame`, `framesIn`, `bytesFor`, `nanosFor`, `framesFor`. The
duration conversions split into whole seconds plus a remainder, because
`frames * 1_000_000_000` leaves Long's range after about fifty-three hours at
48 kHz, which a long-running process reaches and a unit test does not.

## Nobody here resamples, so do not resample for us

Open the sink at the rate the media actually is.

The reason is not that resampling is hard or that the platform is better at it.
It is that converting samples is an addon's work, and the addon does not exist
yet. This library describes audio and carries it. Changing it belongs on the
other side of the decorator seam, in something published separately that a
consumer chooses to add. Putting a resampler in the core would make it a
combine harvester, and it would make our own mistakes about it unremovable
without forking the library, which is the outcome the plugin seam exists to
prevent.

Today that leaves the conversion to the sound server, which does it anyway.
Measured rather than assumed, in all three backends: the PulseAudio one puts
the rate you gave into the sample spec and the server meets it, the WASAPI sink
sets `AUTOCONVERTPCM` because the engine otherwise refuses any format but its
own mix format, and the CoreAudio output unit is told the rate in its stream
description.

So a 44.1 kHz file on a 48 kHz graph is not your problem. Decoding it to 48 kHz
yourself means two conversions where one would do, and the second one happens
whether you like it or not.

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

Named in the plan rather than left to be discovered.

**Presentation time is arithmetic you do yourself.** Where a frame will be heard
is `framePosition` plus `latencyNanos`, converted through `AudioFormat`, and
every consumer writes it out. Section 9.4 makes it one call.

**Channel placement stops at eighteen positions.** The ones libpulse and Windows
both name, which is every standard layout up to 9.1.4 and not the ones with a
second LFE, a bottom row or a wide pair. Those are refused rather than
mis-placed, and an unspecified layout is the way through.

**Two backends take a channel count and nothing more.** JavaSound has nothing to
say it with, and CoreAudio will get it when the oracle has printed the channel
label values. `Capability.CHANNEL_PLACEMENT` is how you find out which kind you
were handed.
