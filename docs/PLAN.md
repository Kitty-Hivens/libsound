# libsound: latency, capture, and how deep Linux goes

What this library is for, and what it grows into. Specified rather than
sketched, so a decision made here does not have to be made again at the
keyboard.

Everything below inherits the rules the library already holds itself to: every
ABI number comes from an oracle, every capability is queried rather than
discovered by failing, every backend passes a contract suite, and every surface
that writes state outliving the process records what it replaced and puts it
back.

---

## 1. Purpose

Four things:

1. **A clean path to the sound server.** Two layers away from it today, and each
   one costs something nameable. JavaSound plays audio and loses everything
   around it: no stream identity, no device selection, no system volume, no
   capture worth the name, which is why the libpulse backend exists at all.
   `pipewire-pulse` gives all of those back and costs the rest, because it can
   only carry what the PulseAudio protocol can say. Measured, in section 13:
   eighteen of the thirty-six channel positions a decoder sends, no 64-bit
   float, and a `node.latency` the shim recomputes and overwrites. Speaking
   PipeWire natively is what removes the second layer.

   What clean is for, concretely: a consumer hands frames to libsound and
   libsound is a node in the graph. Whatever the person at that machine wants
   between the node and their speakers, EasyEffects or any other filter, is a
   link they make. This library's job is to be an ordinary node they can route,
   not to be told about it.

2. **Low latency.** Audio that arrives when it should. A keypress heard in
   single-digit milliseconds, not in a fifth of a second.
3. **MPRIS.** A player the desktop drives, and a reader that drives everyone
   else's.
4. **A seam deep enough to build on.** The sound control an Android device has,
   brought to the JVM, without this library growing it. That means an API that
   describes audio precisely enough for somebody else's plugin to act on, and a
   decorator boundary that plugin hangs off. Section 5.5 is the boundary and
   section 10 is what stays outside it.

The fourth is the one that shapes the other three. A library that only solved
the problem in front of us would be a library the next problem needs a fork of.

### 1.1 How far it has got

Nothing built so far is being walked back. Every backend, every contract and
every capability already written stays exactly as it is, and the rows below are
the distance between what exists and what the purpose asks for, not a list of
mistakes.

The table below is what this plan was written against. Sections 4, 5, 6.1, 6.4,
7, 8.1, 8.2 and 8.4 have since been built on Linux, and every row of it is now
closed there:

| | Then | Now |
|---|---|---|
| Buffer target | 200 ms on every backend | `LatencyProfile`, defaulting to 40 ms. 200 ms is `RELAXED` and still exact. |
| `PA_STREAM_ADJUST_LATENCY` | Set on the metering stream, not on the playback stream | Set on the playback stream and the capture stream, which is what makes a request a request. |
| `minreq` | Left at server default | A quarter of the target, never below a frame. |
| What reaches PipeWire | A 200 ms request | What the server granted is read back and logged at open. Measured: 200 ms granted 150, 40 granted 30, 10 granted 16, which was the graph quantum. |
| Writer thread priority | Ordinary JVM thread | RealtimeKit, opt-in, with `RLIMIT_RTTIME` set first and the kernel's answer asserted through `/proc`. |
| Underruns | Not counted | Counted by the PulseAudio and CoreAudio backends, with `UNDERRUN_COUNT` saying which numbers mean anything. |

What is not built is stated where it belongs: Windows capture in 6.2, the
processing module in 5.5 and 10, and the two MPRIS interfaces of 8.3.

---

## 2. Scope

### 2.1 Platform tiers

| Tier | Platform | What it means |
|---|---|---|
| First class | Linux | Every feature lands here first and stays supported. libpulse covers PulseAudio and PipeWire through one binding, and the PipeWire-specific levers go through the same connection. |
| Experimental | Windows | Ships, reports capabilities honestly, and never blocks a release. The ABI executes under wine on every push. Hardware behaviour is unproven. |
| Output only | macOS | The CoreAudio sink stays and stays tested. Nothing new is added there. |

A tier is a promise about where effort goes, not a claim about quality. An
experimental backend is held to the same contract suite as a first-class one.
What differs is whether a gap there blocks anything.

### 2.2 What "experimental" obliges

A Windows-only defect does not hold a release. A Windows-only feature is not a
prerequisite for a Linux feature. The capability set stays truthful regardless:
a backend reports what its code can do, and the tier is stated once, in the
README, rather than by withholding capabilities the code does provide.

### 2.3 What macOS keeps

`CoreAudioBackend`, `CoreAudioSink` and `MpNowPlayingSession` remain, with their
suites running on every push. Neither grows. Capture there is a non-goal, see
section 10.

---

## 3. Current state

Three modules. `libsound-core` carries the contracts and no dependencies at all.
`libsound-audio` carries the output channel and the mixer. `libsound-session`
publishes and reads media sessions.

| Surface | State |
|---|---|
| `AudioSink`, `AudioBackend` | Done. Pulse, WASAPI, CoreAudio, JavaSound fallback. |
| `VolumeMixer` | Done for both directions on Pulse. Playback only on WASAPI. |
| `MediaSession`, `SessionReader` | Root and Player interfaces, including the optional repeat, shuffle and fullscreen properties and the root's two methods. MPRIS both directions, SMTC and MPNowPlayingInfoCenter publish. |
| Low latency | Done on Pulse, with a real-time writer thread behind an opt-in. Not honoured by the other three, which say so through `LOW_LATENCY`. |
| Capture | Done on Pulse and JavaSound, including recording one application on its own. Absent on Windows and macOS, for the reasons in 6.2 and 10. |
| Device and card control | Done on Pulse: device volume and mute, the default, ports, card profiles, virtual and combined sinks, and a sample cache. |
| Processing | Done. `libsound-dsp`, depending on `libsound-core` alone: a gain, a biquad, a limiter and a tap, each passing the decorator fixture, and the stack of them passing it too. |
| What a format says | Done. Five encodings, a channel layout naming each channel, and significant bits. What a sink accepts is asked through `accepts` before an open rather than caught after one, and the pair is asserted against every backend. |
| Channel placement | Done on Pulse, WASAPI and PipeWire, all from oracle tables. Absent on JavaSound, which has nothing to say it with, and on CoreAudio until the oracle prints the channel labels. |
| PipeWire, natively | A stream in each direction passing both contract suites against a live graph, a device list from the registry, device events, and the stream's own volume as a control on its node. Missing: which device is default, and a device's own volume, both behind binding a proxy. Not on the selection path until it passes what the libpulse backend passes, and reachable through `-Dlibsound.backend=pipewire` meanwhile. |
| Publication | 0.1.0 on Maven Central, five artifacts under `dev.hivens`. |

---

## 4. Latency

The largest piece, and the one the library exists for.

### 4.1 The numbers

At 48 kHz, one buffer of:

| Frames | Latency | What it is for |
|---|---|---|
| 9600 | 200 ms | Today's default on every backend. |
| 1024 | 21.3 ms | PipeWire's own default quantum. |
| 256 | 5.3 ms | A game, a synth, anything a keypress should be heard in. |
| 128 | 2.7 ms | What the hardware can do, and what a loaded machine will underrun on. |

The quantum is a floor, not a suggestion: a client asking for less than the
graph's `clock.quantum` gets the quantum. 1024 is PipeWire's default and desktops
configured for audio work run lower, so the reachable floor is a property of the
machine rather than of this library. Section 4.4 has the measurement.

A round trip through capture and back out is roughly twice the number plus the
device's own path, so a voice application aiming to feel live is aiming at 256
or below on each side.

### 4.2 A profile, not a magic number

`SinkConfig.bufferNanos` stays as the escape hatch for a caller that knows
exactly what it wants. Most callers do not, and the honest interface is a named
target:

```kotlin
public enum class LatencyProfile(public val targetNanos: Long) {
    /** 200 ms. Never underruns. For a file player nobody is interacting with. */
    RELAXED(200_000_000L),

    /** 40 ms. Video sync and music. The default. */
    BALANCED(40_000_000L),

    /** 10 ms. A keypress is heard as a keypress. */
    LOW(10_000_000L),

    /** 5 ms. What the machine can do, and it will underrun on a busy one. */
    LOWEST(5_000_000L),
}
```

`SinkConfig` and `SourceConfig` carry `latency: LatencyProfile = BALANCED`.

**Changing the default from 200 ms to 40 ms is deliberate** and belongs in the
changelog as a behaviour change. The old number was chosen so nothing would ever
stutter. That is the right default for a library that plays files and the wrong
one for a library whose first stated purpose is latency. A consumer that wants
the old behaviour asks for `RELAXED` and gets it exactly.

### 4.3 PulseAudio: asking for it properly

Four changes to `PulseSink`, three of which are one line each.

**Set `PA_STREAM_ADJUST_LATENCY` on the playback stream.** The constant is
already in `PulseAbi` and `PulseMeter` already sets it, on the metering stream,
where the latency it controls is of no consequence. `PulseSink` does not set it,
and that is the stream a consumer hears. Without the flag the server treats
`tlength` as a buffer size and keeps its own path as long as it likes. With it,
`tlength` becomes the latency the client is asking for and the server adjusts
everything it controls to meet it. This is the single most important line in
this section, and it is one line.

**Set `minreq` rather than leaving it at default.** It is how much the server
asks for at a time, and leaving it to the server means leaving half the latency
to the server. It goes to roughly a quarter of `tlength`, which is the usual
relationship, and never below one period.

**Let the server size `maxlength`.** It is `tlength * 2` today. With
`ADJUST_LATENCY` set, the server sizes the shared buffer itself and a client
number fights it.

**Add `PA_STREAM_EARLY_REQUESTS` as the fallback.** Where a server refuses to
honour `ADJUST_LATENCY`, early requests still shortens the client-side half.

Symbols and constants to add:

```
PA_STREAM_EARLY_REQUESTS
pa_stream_set_underflow_callback
pa_stream_set_overflow_callback
pa_stream_get_buffer_attr        <- already bound
pa_stream_get_latency            <- already bound
```

### 4.4 PipeWire: what the lever actually is

An earlier draft of this section said `node.latency` in the stream proplist is
how a client asks PipeWire for a quantum. That is wrong, and the measurement
takes a minute to repeat.

`PULSE_PROP` puts arbitrary properties on a stream's proplist and they arrive
intact: an invented `libsound.probe` key shows up in `pactl list sink-inputs`
exactly as set, and so does `media.role`. `node.latency` set the same way does
not survive. pipewire-pulse computes it from the pulse latency request and
overwrites whatever the client wrote.

| Latency asked for through the pulse API | What the graph ended up with |
|---|---|
| 200 ms | `node.latency = 3840/48000`, 80 ms |
| 40 ms | `node.latency = 480/48000`, 10 ms |
| 10 ms | `node.latency = 256/48000`, 5.3 ms |

`node.latency=128/48000` was set in the proplist for all three and ignored in
all three.

So there is no separate PipeWire path to write. The lever is the one section 4.3
already describes: ask through `tlength` with `ADJUST_LATENCY`, and
pipewire-pulse does the translation. That makes 4.3 more important rather than
less, and it removes a section of work from this plan.

**The graph quantum is a floor.** The third row asked for 10 ms and got 5.3,
which is `clock.quantum` of 256 frames on the machine it was measured on. A
client does not get below the quantum by asking politely. PipeWire's own default
is 1024, which is 21 ms, so on a desktop nobody has configured, the `LOW` and
`LOWEST` profiles of 4.2 are not reachable by a client at all.

Going below the quantum means `node.force-quantum` or `node.lock-quantum`, which
change the graph for every other application on the machine. Both are exposed,
documented as antisocial, and off. A library that quietly reconfigures a user's
whole desktop to serve one application is doing something it was not asked to
do.

**Which is why a profile is a request and a ceiling, not a promise.** The sink
reports what it was actually granted, through `latencyNanos` and through one
line at open, so a consumer that asked for `LOWEST` on a 1024-quantum desktop
can find out it got 21 ms without a packet trace.
`pa_stream_get_buffer_attr` after the stream is ready is where that number comes
from.

### 4.5 The thread has to be real-time

A 5 ms buffer means the writing thread wakes every 5 ms and is never late. An
ordinary JVM thread competing with a compile, a browser or a game loop will be
late, and every time it is late the audio underruns. This is the part that makes
the difference between asking for low latency and having it.

Linux offers this through RealtimeKit, on the system bus:

```
org.freedesktop.RealtimeKit1
  /org/freedesktop/RealtimeKit1
  MakeThreadRealtime(uint64 thread, uint32 priority)
```

Three pieces of work:

1. **The kernel thread id.** RealtimeKit wants the tid, not the JVM's thread
   object. `gettid()` is in glibc from 2.30 and is a plain Panama downcall.

2. **`RLIMIT_RTTIME` must be set first.** RealtimeKit refuses a process that has
   not limited how long it may spend at real-time priority, which is the
   mechanism that keeps a buggy client from locking the machine.
   `setrlimit(RLIMIT_RTTIME, ...)` before the request.

3. **A D-Bus connection from `libsound-audio`.** The D-Bus layer lives in
   `libsound-session` today and the audio module does not depend on it. Three
   ways out, see the open questions in section 11.

This is opt-in. `SinkConfig.realtime: Boolean = false`, and true only where a
caller asked for `LOW` or `LOWEST` and said it wants the thread promoted.
Silently taking real-time priority is not something a library should do to a
process that did not ask.

Failure is not fatal and not silent: if the promotion is refused, the sink logs
it once with the reason and reports `Capability.REALTIME_THREAD` absent, so a
consumer showing an audio settings screen can say why the lowest profile is
unavailable rather than letting the user pick a setting that will crackle.

### 4.6 Knowing when the target was too aggressive

Low latency without underrun reporting is a setting nobody can validate.

`pa_stream_set_underflow_callback` and its overflow counterpart are bound, and
the sink exposes what they counted:

```kotlin
public interface AudioSink {
    /** Times the device ran dry since open. Monotonic within one open. */
    public fun underrunCount(): Long
}
```

A consumer watching that number climb backs its profile off. `PcmRingBuffer`
already counts underruns and overruns for the same reason and the vocabulary
matches.

### 4.7 What `latencyNanos` should mean

Today it reports what is queued in the client's own buffer. For a library whose
first purpose is latency, the number a consumer needs is the whole path: client
buffer plus server plus device. `pa_stream_get_latency` reports exactly that and
is already bound.

The contract states which one it is, and the answer is total. A video pacer
adding its own estimate of the server's contribution is a pacer that will get it
wrong differently on every machine.

---

## 5. Contracts

New and changed types in `libsound-core`. This module has no dependencies and
gains none.

### 5.1 `AudioSource`

The mirror of `AudioSink`. Same lifecycle, same rules, opposite direction.

```kotlin
public interface AudioSource : AutoCloseable {

    public val capabilities: Capabilities

    /** The format currently open, or null before the first open and after close. */
    public val format: AudioFormat?

    public val isOpen: Boolean

    /**
     * Open the device for [format] and start it. Reopening replaces the stream
     * and restarts the frame position at zero.
     */
    public fun open(format: AudioFormat)

    /**
     * Fill [length] bytes at [offset], blocking until the device has produced
     * all of them. [length] must be a whole number of frames.
     */
    public fun read(dst: ByteArray, offset: Int, length: Int)

    public fun start()

    public fun stop()

    /** Discard captured-but-unread audio. Valid while stopped. */
    public fun flush()

    /** Sample frames the device has captured since open. Frozen while stopped. */
    public fun framePosition(): Long

    /** How far behind the microphone the read head is. The whole path. */
    public fun latencyNanos(): Long

    /**
     * Frames the device produced and nobody read, because a consumer fell
     * behind. Monotonic within one open.
     */
    public fun overrunFrames(): Long

    public fun setVolume(volume: Float)

    public fun volume(): Float

    override fun close()
}
```

**Why `read` blocks fully rather than returning a count.** It mirrors
`AudioSink.write`, which blocks until the device took every byte, and for the
same reason: the call returning is what tells a consumer that time has passed.
A recorder writing to a file wants a filled buffer. A consumer that wants the
newest frames rather than all of them is asking a different question, and the
answer is a smaller buffer, not a short read.

**Why overruns are counted rather than thrown.** A slow consumer is an ordinary
condition on a shared machine. Losing frames silently is the failure, so the
count is available and never zero-by-omission.

**Contract rules, asserted by a suite mirroring `AudioSinkContract`:**

1. `open` starts the device. A consumer wanting silence calls `stop` after.
2. `open` resets the frame position to zero.
3. `read` blocks until the device has produced the frames.
4. `stop` freezes the position, `start` resumes it.
5. `flush` is valid while stopped and discards what has not been read.
6. `framePosition` need not be monotonic across a flush.
7. `close` unblocks a `read` in flight.
8. `close` is idempotent and never throws.

### 5.2 `SourceConfig`

Mirrors `SinkConfig`, including the latency profile. The identity fields matter
more here than on the output side, because a capture stream shows in a privacy
indicator: a desktop saying "Nexira is using your microphone" is reading exactly
these.

```kotlin
public data class SourceConfig(
    public val applicationName: String,
    public val applicationId: String? = null,
    public val iconName: String? = null,
    public val mediaRole: MediaRole = MediaRole.MUSIC,
    public val device: DeviceId? = null,
    public val latency: LatencyProfile = LatencyProfile.BALANCED,
    public val bufferNanos: Long? = null,
    public val realtime: Boolean = false,
)
```

### 5.3 Direction on a stream

`VolumeMixer` grows to cover capture rather than gaining a sibling. A person
looking at their machine sees one picture of it, and a shell that wants only one
half filters.

```kotlin
public enum class StreamDirection { PLAYBACK, CAPTURE }
```

`AudioStream` gains `direction: StreamDirection`, defaulting to `PLAYBACK` so
the change is source-compatible for anything constructing one.

`VolumeMixer.streams()` returns both directions once a backend supports it. That
is a behaviour change for existing consumers, acceptable before 1.0 and stated
in the changelog: a consumer drawing a playback panel filters on `direction`,
and the capability says whether capture rows can appear at all.

`setVolume`, `setMuted` and `moveTo` work on either direction. `moveTo` on a
capture stream moves it to another source device.

### 5.4 Capture devices

`AudioBackend` gains a parallel accessor rather than overloading the existing
one, because a consumer almost always wants one list or the other and never a
mixed one:

```kotlin
public fun captureDevices(): List<AudioDevice>

public fun defaultCaptureDevice(): AudioDevice?

public fun createSource(config: SourceConfig): AudioSource
```

`onDevicesChanged` covers both. The event is already coarse, it says something
moved rather than what, and splitting it would be state to keep correct for no
gain.

### 5.5 The decorator rule

Processing is a sink that wraps a sink. That works today because `AudioSink` is
a public interface, and it is a trap today because the contract says nothing
about what a wrapper owes.

**What the seam is for.** Not a demonstration that the shape holds, which is how
this section used to read. It is the mechanism by which everything this library
should not contain can be added by somebody else, and by which our own decisions
about processing stay removable. A resampler, a downmixer, an equaliser, a
channel remapper: each is a sink wrapping a sink, published on its own, and a
consumer that disagrees with ours writes theirs instead of forking this. That is
what section 10 means when it says a thing is out of scope, and it is only true
while the seam is documented and asserted rather than merely possible.

Four rules, added to `AudioSink`'s documentation and asserted by a fixture any
decorator can extend:

1. **`write` still blocks until the device took the audio.** A decorator may
   buffer internally, but it may not return before the frames it produced have
   been consumed by the sink it wraps. Returning early turns the consumer's
   decode loop into a busy loop and makes a stall watchdog fire during healthy
   playback.

2. **`framePosition` delegates unchanged.** It counts frames the device has
   played, and a decorator has played nothing. Adding its own buffered frames
   would report audio nobody has heard.

3. **A decorator that changes the frame count must say so.** Resampling and
   time-stretching produce a different number of frames than they consume, so
   the wrapped sink's position no longer counts the consumer's frames. Such a
   decorator either reports positions in the consumer's frames by scaling, or
   declares itself unusable as a clock source by withholding
   `Capability.DEVICE_POSITION`. There is no third option that keeps audio and
   video in sync.

4. **`flush` drops the decorator's own buffer too, and `close` closes what it
   wraps.** A seek that leaves stale samples in a filter plays the old position
   for as long as the filter is deep.

A decorator also adds latency, and `latencyNanos` must include its own buffer on
top of what it wraps. A filter that hides its depth makes every consumer's
synchronisation wrong by exactly that much.

### 5.6 Capabilities

Added to `Capability`, in the existing style: each is a question a settings
screen asks before it draws a control.

| Capability | Meaning |
|---|---|
| `CAPTURE` | The backend can open an `AudioSource` at all. |
| `CAPTURE_ENUMERATION` | Capture streams belonging to other applications can be listed. |
| `CAPTURE_CONTROL` | Volume and mute can be set on somebody else's capture stream. |
| `CAPTURE_ROUTING` | A capture stream can be moved to another source device. |
| `CAPTURE_METERING` | A capture stream's level can be watched. |
| `LOW_LATENCY` | The backend can honour a latency request rather than accepting it politely. |
| `REALTIME_THREAD` | The writing thread was promoted, so the lowest profiles are usable. |
| `DEVICE_VOLUME` | The device's own volume, not just a stream's, can be read and set. |
| `DEVICE_PROFILES` | Card profiles and ports can be listed and switched. |
| `VIRTUAL_DEVICES` | Devices that do not exist in hardware can be created and removed. |
| `PER_STREAM_CAPTURE` | One application's output can be recorded without recording the desktop. |
| `SAMPLE_CACHE` | Short sounds can be uploaded once and triggered by name. |

`STREAM_IDENTITY` is reused rather than duplicated: a source stream carries the
same name, icon and role a sink stream does.

---

## 6. Backends

### 6.1 Linux

The record path is partly written already. `PulseMeter` opens a record stream
today to read peak levels off a monitor source, so `pa_stream_connect_record`,
`pa_stream_peek` and `pa_stream_drop` are bound and exercised. Pointing the same
machinery at a real source is the smaller half of the work.

Symbols to add for capture:

```
pa_context_get_source_info_list
pa_context_get_source_info_by_name
pa_context_get_source_output_info
pa_context_get_source_output_info_list
pa_context_set_source_output_volume
pa_context_set_source_output_mute
pa_context_move_source_output_by_name
```

ABI additions to `PulseAbi`, each printed by `tools/pa-oracle.c` before it is
written down:

```
offsetof(pa_source_info, name / index / description / monitor_of_sink)
offsetof(pa_source_output_info, index / name / source / volume / mute
         / proplist / corked)
PA_SUBSCRIPTION_MASK_SOURCE
PA_SUBSCRIPTION_MASK_SOURCE_OUTPUT
PA_SUBSCRIPTION_EVENT_SOURCE_OUTPUT
PA_STREAM_EARLY_REQUESTS
```

`monitor_of_sink` earns its place. A source that is a sink's monitor is not a
microphone, and a device list offering "Monitor of Built-in Audio" as an input
confuses everyone who reads it. The field is how the two are told apart, and a
consumer may legitimately want either.

`PulseMixer` grows a second enumeration alongside the sink-input walk, keyed the
same way and sharing the round-trip lock. The restore obligation extends
unchanged.

### 6.2 Windows

Two questions, both answerable by the oracle that already exists, and both to be
answered before any of this is written.

**Does `IAudioSessionManager2` enumerate capture sessions?** The documentation
says a session manager activated on a capture endpoint enumerates that
endpoint's sessions. Nothing here has measured it, and the answer decides
whether the Windows half of section 5.3 exists at all.

**What are `IAudioCaptureClient`'s slots? Answered.** `tools/wasapi-oracle.c`
prints them, and this never needed a Windows machine: wine reimplements the same
vtable, which is the one thing it is a good oracle for. `GetBuffer` is slot 3,
`ReleaseBuffer` 4, `GetNextPacketSize` 5, six slots in the table, and the
interface identifier is printed beside them. The shape differs from the render
side as expected: the capture side hands back a frame count, flags and two
timestamps where the render side takes a count.

Additions once both are answered:

```
IID_AUDIO_CAPTURE_CLIENT
CAPTURE_GET_BUFFER
CAPTURE_RELEASE_BUFFER
CAPTURE_GET_NEXT_PACKET_SIZE
E_CAPTURE                        the EDataFlow value, 1 where E_RENDER is 0
```

On latency, Windows has its own path: `IAudioClient3::InitializeSharedAudioStream`
takes a period in frames and `GetSharedModeEnginePeriod` reports what the engine
will accept. That is the Windows equivalent of section 4.3 and it is
experimental-tier work, done after Linux.

### 6.3 macOS

No work. See section 10.

### 6.4 JavaSound

`TargetDataLine` is the fallback's capture counterpart and exists on every JVM.
It reports the same reduced capability set the fallback sink does: no stream
identity, no system volume, no device selection, and no meaningful latency
control. It is worth binding because the fallback's purpose is that something
works everywhere.

---

## 7. Linux depth

The tier says Linux is first class. This section is what that buys, and it is
deliberately the longest one.

### 7.1 The devices themselves, not just the streams

Today a stream's volume can be changed and the device it plays to cannot. That
is half a mixer. A shell drawing the panel a user expects needs the other half.

```
pa_context_set_sink_volume_by_name
pa_context_set_sink_mute_by_name
pa_context_set_source_volume_by_name
pa_context_set_source_mute_by_name
pa_context_set_default_sink
pa_context_set_default_source
pa_context_suspend_sink_by_name
```

`AudioDevice` gains `volume`, `muted` and `isSuspended`. `VolumeMixer` gains
`setDeviceVolume`, `setDeviceMuted` and `setDefaultDevice`, all behind
`Capability.DEVICE_VOLUME`, all carrying the same restore obligation as a
stream's volume.

Setting the default device is the one operation here that changes what happens
to applications that have nothing to do with the caller. It is behind its own
capability check and it is documented as a user-facing action, not a
housekeeping one.

### 7.2 Cards, profiles and ports

This is what pavucontrol's configuration tab does, and nothing in this library
can do any of it: choosing headphones over speakers on the same card, enabling
an HDMI output that is present but off, switching a bluetooth headset between
high quality playback and the low quality mode that has a microphone.

```
pa_context_get_card_info_list
pa_context_get_card_info_by_name
pa_context_set_card_profile_by_name
pa_context_set_sink_port_by_name
pa_context_set_source_port_by_name
```

New types, deliberately small:

```kotlin
public data class CardProfile(
    public val name: String,
    public val description: String,
    /** Zero means the profile exists but nothing is plugged into it. */
    public val priority: Int,
    public val available: Boolean,
)

public data class DevicePort(
    public val name: String,
    public val description: String,
    public val available: Boolean,
)

public data class AudioCard(
    public val id: String,
    public val name: String,
    public val profiles: List<CardProfile>,
    public val activeProfile: String?,
)
```

The A2DP and headset-mode switch is the case that justifies the whole section:
it is the difference between a bluetooth headset that sounds good and one whose
microphone works, and no application can currently make that choice for the user
who asked for it.

### 7.3 Recording one application

`pa_stream_set_monitor_stream` is already bound and already used, by
`PulseMeter`, to narrow a monitor stream to a single sink input so a level meter
reads one row rather than the whole device. The same call with a real record
stream behind it records one application's output and nothing else.

That is per-application capture without a virtual device, without routing, and
without the target application knowing. Recording a game's audio while a voice
chat plays through the same speakers, capturing one browser tab, feeding one
application into a stream: all of it is a record stream plus a call that is
already in the binding.

Behind `Capability.PER_STREAM_CAPTURE`, and worth stating in the same breath:
this reads another application's audio output. It belongs behind a capability so
a consumer cannot offer it where it will not work, and it belongs in the
documentation as what it is.

### 7.4 Virtual devices and routing

`pa_context_load_module` creates a sink that does not exist in hardware.
Combined with the stream routing that already works, an application can be moved
into it without touching that application's own settings. A soundboard, a
separate voice bus, game audio split from music: all of it is this plus what is
already written.

```kotlin
public interface VolumeMixer {
    /**
     * Create a device that does not exist in hardware. Null where
     * [Capability.VIRTUAL_DEVICES] is absent or the server refused.
     *
     * The returned device is this process's to remove. [close] removes every
     * device it created and did not already remove, the same obligation and for
     * the same reason as a volume it lowered.
     */
    public fun createVirtualSink(name: String, channels: Int = 2): DeviceId?

    /** Remove a device this process created. False for one it did not. */
    public fun removeVirtualSink(id: DeviceId): Boolean

    /** Play the same audio to two devices at once, until removed. */
    public fun combineSinks(name: String, devices: List<DeviceId>): DeviceId?
}
```

```
pa_context_load_module
pa_context_unload_module
pa_context_get_module_info_list   <- already bound
```

The restore obligation matters more here than anywhere else in the library. A
virtual sink left behind after a crash is not quiet audio a user can fix in
their mixer, it is a device in their settings that nothing owns and nothing will
remove. `restoreAll` therefore unloads modules before restoring volumes, because
a stream restored onto a device that is about to vanish ends up somewhere nobody
chose.

### 7.5 The sample cache

The lowest-latency path there is for a short sound, and it does not involve a
stream at all. A sound is uploaded to the server once and afterwards triggered
by name: no stream setup, no buffer to fill, no scheduling. For interface clicks
and notification sounds this is the difference between a sound that lands with
the click and one that lands after it.

```
pa_stream_connect_upload
pa_stream_finish_upload
pa_context_play_sample
pa_context_remove_sample
```

```kotlin
public interface AudioBackend {
    /** Upload a short sound, once. Null where the server refused. */
    public fun cacheSample(name: String, format: AudioFormat, pcm: ByteArray): SampleId?

    /** Trigger an uploaded sound on a device. Returns false when it is gone. */
    public fun playSample(id: SampleId, device: DeviceId? = null, volume: Float = 1f): Boolean
}
```

Samples the process uploaded are removed on close, the same obligation as
everything else here.

### 7.6 Ending somebody else's stream

`pa_context_kill_sink_input` and `pa_context_kill_client` end another
application's audio. This is in the binding's reach and is deliberately **not**
specified as a feature. It is listed here so that the decision is on record: a
mixer that can silence an application by muting it does not also need to be able
to disconnect it, and the two look identical to a user while only one is
reversible.

### 7.7 What Linux still will not do

A client cannot cork another client's stream. `pa_stream_cork` operates on a
stream the caller owns and libpulse offers no route in from outside, so "pause
everything else" is not available. The honest substitutes are mute or a media
role. Stated here so it is not rediscovered.

**PipeWire natively** has its own section now. An earlier draft of this
paragraph excluded it as a second complete binding for a benefit section 4.4
already delivered most of. That was true of latency and false of everything
else, which section 13.1 measures: the compatibility layer decides what this
library can say about a stream, and it says less than the graph can hear.

---

## 8. MPRIS depth

The third stated purpose. 8.1, 8.2 and 8.4 are built. 8.3 is named and not
taken.

### 8.1 Properties a widget already expects

`LoopStatus` and `Shuffle` are ordinary Player properties, they are writable,
and nothing here published them. A desktop widget with repeat and shuffle
buttons could not drive this player, because the properties it would set did
not exist on the object.

```
LoopStatus   "None" | "Track" | "Playlist"    read and write
Shuffle      boolean                          read and write
```

Both arrive as commands the way `Volume` already does, through
`Properties.Set`, and both went into `SessionState` so a consumer publishes them
with everything else.

**They are carried as optional, which this plan did not anticipate.** Both are
optional in the specification, so a `SessionState` that always carried them
would put a repeat button on every player this library publishes, including a
radio stream with nothing to repeat.
So the fields are nullable: null means there is no such notion and the property
is absent from the interface, `LoopMode.NONE` means there is a queue and it is
not repeating, and the two are different answers everywhere the session is
asked. `GetAll` omits an absent property, `Get` answers `UnknownProperty`, `Set`
is refused, and the introspection document leaves it out, because that document
is where a widget decides what to draw. A property that stops being carried is
announced through the invalidated array of `PropertiesChanged`, which is the
only thing the protocol offers for a property that is no longer there.

Each of the three is behind a capability, which this plan did not ask for and
section 5.6's own rule does: `SessionState` is shared by three platforms and
only MPRIS carries any of them, so a consumer publishing a repeat mode on
Windows was publishing into nothing and had no way to ask first.

`Rate` is still refused with `NotSupported`, which is honest while nothing acts
on it. Once a consumer can act on it, it becomes a command like the others.

`OpenUri` is still refused for the same reason and the same path applies.

### 8.2 Root properties

`Fullscreen` and `CanSetFullscreen` are Root properties a video player is
expected to carry, and they were cheap as predicted: a boolean in
`SessionConfig` for whether the desktop may set it, a nullable boolean in
`SessionState` for what it currently is, and a command when a desktop sets it.
The pair appears and disappears together, since whether the desktop may change a
state is worth nothing beside a state nobody publishes. A change to it is
announced on the root's own interface rather than the player's, because a
`PropertiesChanged` names the interface its properties belong to.

`Raise` and `Quit` were answered and dropped, which made `canQuit` and
`canRaise` decoration: a consumer could claim either and the desktop would draw
a control that reached nothing. Both now arrive as commands, gated on what the
configuration advertised.

### 8.3 The interfaces not yet touched

`org.mpris.MediaPlayer2.TrackList` is the queue: what is coming, what came
before, and the means to reorder it. `org.mpris.MediaPlayer2.Playlists` is the
set of playlists a player offers.

Both are optional in the specification and both are what a rich desktop widget
reads when it offers more than transport buttons. Neither is required for the
library's stated purpose, and both are named here so their absence is a decision
rather than an oversight.

### 8.4 On the reading side

`SessionReader` reads Root and Player. The same additions applied in reverse: a
reader that can see another player's `LoopStatus` and `Shuffle` can draw the
buttons for them, and one that cannot leaves them out. `ForeignPlayer` carries
the three optional properties with the same null, and `canRaise`, `canQuit` and
`canSetFullscreen` beside them, so a widget asks before it draws rather than
finding out by being refused. `control` sends all of them, each on the interface
that owns it.

---

## 9. Numbers a consumer's interface needs

### 9.1 Volume domain

`VolumeMixer` and `AudioSink` report linear amplitude, converted through
`pa_sw_volume_to_linear`. A slider mapped straight onto it feels dead at the
bottom and jumpy at the top, because desktop mixers draw a cubic curve.

```kotlin
public enum class VolumeCurve { LINEAR, CUBIC }
```

`AudioStream.volume` stays linear with `volumeCubic` beside it, and the setters
take the curve. A consumer drawing a slider uses cubic and matches what the
desktop shows. A consumer computing a duck factor uses linear and gets the
arithmetic it expects.

### 9.2 Channel maps

**Done, and it was a live defect rather than a refinement.** `AudioFormat`
counted channels and did not name them, and the PulseAudio sink connected its
stream with a null channel map, which means the server applies its own default.
The oracle prints what that default is, and it is not a reordering of what a
decoder sends: six channels resolve to front-left, front-left-of-center,
front-center, front-right, front-right-of-center, rear-center, with no LFE in it
at all. A 5.1 stream in FFmpeg's order played its right channel out of a
front-left-of-center speaker and its LFE out of the front right at full level,
from three channels upward, silently.

`ChannelLayout` names the positions, generated from `ffmpeg -layouts` and
`tools/ffmpeg-layout-oracle.c`, and `AudioFormat.layout` carries one. libpulse
gets a `pa_channel_map` and Windows a `dwChannelMask`, both from oracle-printed
tables, and both say so through `Capability.CHANNEL_PLACEMENT`. JavaSound has
nothing to express it with; CoreAudio waits on the oracle for
`kAudioChannelLabel_*`.

Eighteen of FFmpeg's thirty-six positions have an equivalent on both platforms.
A layout naming one of the rest is refused rather than carried with a channel
missing, and `ChannelLayout.unspecified` is the documented way to take the
platform's own ordering instead.

### 9.3 Sample formats

**Done.** `PcmEncoding` carries five: `U8`, `S16LE`, `S32LE`, `F32LE`, `F64LE`.
There is no `S24LE`, and deliberately: FFmpeg has no 24-bit sample format, so
24-bit content arrives as `S32LE` with the value in the top bits, and
`AudioFormat.significantBits` is how a backend tells that apart from a stream
that genuinely uses all 32.

What "accepted by every backend" turned out to mean is that it is not, which is
what `AudioSink.acceptedEncodings` and `AudioSink.accepts` are for: libpulse has
no 64-bit float, the JavaSound fallback takes three of the five and the JVM is
asked rather than assumed, and WASAPI takes all five now that it writes a
`WAVEFORMATEXTENSIBLE` instead of a plain `WAVEFORMATEX`.

### 9.4 Presentation time

`framePosition` answers frames played. A video pacer wants to know when a frame
about to be written will be heard, which is that plus the total latency of
section 4.7. Exposing it as one call removes the arithmetic from every consumer,
and removes the chance of each getting it differently wrong.

---

## 10. Non-goals

**Decoding, resampling and effects in core.** skinema decodes. Everything that
changes samples belongs on the far side of the seam in 5.5, in an addon a
consumer opts into, and the reason is not that the platform does it better. It
is that a library which converts audio as well as describing and carrying it is
a library nobody can correct without forking: our choice of resampler, our
downmix coefficients and our idea of what a device wants would all be load
bearing and none of them would be removable. The line is description in the
core, transformation in an addon. Encodings, channel layout, significant bits
and telling a backend its channel map are description. Resampling, downmixing
when a device refuses a layout, narrowing a bit depth and the ladder down to
S16LE stereo are addons.

Which addon nobody has written yet, so today the conversion falls to the sound
server, and that much is measured rather than assumed: the PulseAudio backend
puts the consumer's rate in the sample spec and the server meets it, the WASAPI
sink sets `AUTOCONVERTPCM` because the engine otherwise refuses any format but
its own, and the CoreAudio output unit is told the rate in its stream
description. So nothing in this stack resamples, and libsound takes frames that
are already frames.

The processing module of section 5.5 is opt-in, published as its own artifact,
and depends on `libsound-core` only. It is a fifth module in this repository
rather than a separate one, for the reasons in section 11.

**Mixing sample streams.** `VolumeMixer` moves sliders and never sums audio.
That is why it stopped being called `AudioMixer`.

**macOS capture.** Microphone access there needs a bundle, a signature and a
live user session. No runner can answer whether it works, and writing a backend
whose only verification is somebody's word is the failure mode this repository
exists to avoid.

**Ending another application's audio.** See 7.6.

**Locking the graph quantum by default.** See 4.4.

**Bundled natives.** The published jars contain no platform code and that stays
true. Binding what the system already has is why musl and store-only
distributions work at all.

**Telemetry in any form.** Nothing counts usage, nothing reports home, and there
is no opt-in switch that could later default the other way. A library that can
enumerate microphones and record another application's output has to be
trustworthy about it, and the only trustworthy version is the one with no such
code in it.

**A DSP suite.** The processing module ships a gain, a biquad and a limiter to
prove the seam holds and to give the decorator fixture something to test. The
seam is public so somebody else's project can go further.

---

## 11. Open questions

| Question | Blocks | Answer |
|---|---|---|
| Where does `libsound-audio` get a D-Bus connection for RealtimeKit? | 4.5 | **Answered.** The D-Bus layer moved to `libsound-dbus`, which both modules depend on. Its types are public because a Kotlin `internal` cannot cross a module boundary, and fenced behind an opt-in marker that carries what `internal` used to. |
| Does `ADJUST_LATENCY` behave the same through `pipewire-pulse` as on PulseAudio? | 4.3 | **Measured on pipewire-pulse 1.6.8.** The request is honoured and shortened: 200 ms granted 150, 40 granted 30, 10 granted 16, which was that machine's `clock.quantum` and the floor under everything. What a native PulseAudio grants is still unmeasured, and the sink reports what it got either way. |
| What is the lowest profile that survives on an ordinary desktop? | 4.2 | **Still open, and now answerable by a consumer rather than by this plan.** `underrunCount` is what a soak would read, and the granted number is logged at open. |
| Does `IAudioSessionManager2` enumerate capture sessions? | 6.2 | **Still open, and now askable.** `tools/wasapi-capture-probe.c` puts the question to a real machine, printing the render endpoint beside the capture one so a quiet machine is not mistaken for an endpoint that does not enumerate. `IAudioCaptureClient`'s slots are no longer part of this: the oracle prints them and wine answers correctly. |
| Does the peak-detect path work on a real source as it does on a monitor? | 5.6 `CAPTURE_METERING` | **Answered, in the negative.** `pa_stream_set_monitor_stream` narrows a monitor to one sink input because a monitor carries everything its sink plays. A real source has no equivalent call, so the only level available for a capture row is the device's own, shared by everything reading it. A row that moved because somebody else was talking would be worse than no meter, so the capability is absent. |
| Where does the processing module live? | 5.5 | **Answered: a fifth module in this repository, depending on `libsound-core` only.** Not inside `libsound-audio`, which binds libpulse, because then everyone who wants to play a sound carries filters they never use, and the seam needs no privileged access: it is the public `AudioSink`. Not a repository of its own either, because "separately published" is about the artifact, and a second repository is a second version to keep in step and a second CI for a module whose whole surface is one interface. `libsound-dbus` was reasoned about the same way and stayed here. |
| Does `streams()` returning both directions break a consumer badly enough to warrant a separate call? | 5.3 | **Answered: no.** It returns both, rows carry a direction, and stream ids now name the facility they came from, because a sink input and a source output can hold the same index at once. |
| Is a native PipeWire binding worth a second complete implementation? | 7.7, 13 | **Answered: yes, and it is a purpose rather than an option.** `tools/pipewire-oracle.c` measured what the shim costs: eight channel positions, the 64-bit and packed 24-bit formats, and the latency the node asked for. Those are refusals this library currently reports as its own. Section 13 specifies the backend, and it is built and passing the contract suites. |
| Should the native backend go first on a machine that has both? | 13.9 | **Open, and now a trade with one item on each side rather than a list.** The two backends report the same capabilities except the sample cache, which the graph has no equivalent of. Until that is decided the native path is reached with `-Dlibsound.backend=pipewire`. |
| Does `latencyNanos` returning zero mean unmeasurable or empty? | 4.7 | **Open, and narrowed.** `Capability.TOTAL_LATENCY` now says whether the number covers the device or only the client's queue, which was the larger half of the confusion. It does not separate a backend that cannot measure from a device with nothing queued, which `UNDERRUN_COUNT` does for its own number. |

---

## 12. Sequencing

The order is not arbitrary and the reasons matter more than the order.

**0. Publish.** Nothing can depend on this library, so nothing does. That blocks
the skinema adapter, blocks any consumer, and leaves every API decision free to
change, which is why the naming has to be settled first. Publication converts
decisions into commitments and that is the point of doing it early.

**1. Latency.** The stated purpose, and currently absent. Sections 4.3 and 4.4
are a handful of lines each and change what the library is. 4.5 is the largest
single item in the plan and is what makes the lowest profiles real rather than
requested.

**2. Capture.** The work that changes what the library is for. It is also the
larger half of the decorator argument: a shape that mirrors correctly on both
sides is worth building a plugin seam on.

**3. Linux depth.** Section 7, in the order 7.1, 7.2, 7.3, 7.4, 7.5. Device
volume first because a mixer without it is half a mixer, then cards because the
bluetooth case is the one users hit, then per-application capture because it is
nearly free once capture exists.

**4. MPRIS depth.** Done, except 8.3. Section 8.1 was to come first and alone if
nothing else was done, because `LoopStatus` and `Shuffle` are two properties
standing between this player and every desktop widget that draws more than
transport buttons. 8.2 and 8.4 came with them, for a handful of lines each.

**5. The processing seam.** Done. The contract rules were most of the work, as
predicted, and the reference filters were the rest. What was not predicted is
that the first real consumer would want none of them: a visualiser needs the
samples rather than a change to them, so the module ships a tap beside the
three, and it is the cheapest decorator there is.

**6. The numbers.** Partly done, and not the refinement this line called them.
9.2 and 9.3 turned out to be a defect rather than a polish: a format that says
only how many channels there are is a format a backend lays out by its own
convention, and the conventions disagree from three channels upward. 9.1 and 9.4
are still refinement.

**7. Hardware.** Waits on a person with a Windows machine and gates none of the
above. `docs/TESTING.md` is what that person reads.

**8. Native PipeWire.** Section 13. It is a purpose rather than a refinement,
and it comes after the numbers because the numbers are what told it apart from
a preference: the refusals section 9.2 and 9.3 made this library report are the
compatibility layer's, and a native path is what stops them being ours.

---

## 13. PipeWire, natively

The second layer this library is away from the sound server, and the one
section 1 puts first.

### 13.1 What the compatibility layer costs

Not an argument from architecture. `tools/pipewire-oracle.c` asks the headers,
and the answers are the specification for this section.

| | Through `pipewire-pulse` | Natively |
|---|---|---|
| Channel positions, of the 36 FFmpeg names | 18 | 26 |
| 64-bit float | Not in `pa_sample_format_t` at all | `SPA_AUDIO_FORMAT_F64_LE` |
| Packed 24-bit | `PA_SAMPLE_S24LE` exists and no `PcmEncoding` reaches it | `SPA_AUDIO_FORMAT_S24_LE` |
| `node.latency` | Computed from the pulse request and overwritten, measured in 4.4 | A property the node sets |

The eight positions the shim costs are the wide pair, the second low frequency
channel, the top side pair and the bottom row. Between them they are four of
the forty layouts FFmpeg names: `9.1.6`, `7.2.3`, `hexadecagonal` and `22.2`.
The libpulse backend refuses all four and the native one takes every layout in
the set, which `PipeWireBackendTest` asserts by opening each of them.

That matters more than the count suggests, because of how those refusals read.
`AudioSink.accepts` answers false and `open` throws, and the message says this
server has no channel position for it. A consumer reasonably concludes the
machine cannot play the file. The machine can: PipeWire has the position and
the shim does not, and libsound is reporting the shim's ceiling as the
platform's.

Latency is the one section 4.4 already measured and the one this section is
least about. The shim honours a request and shortens it, which is most of what
a client wants. What it does not do is let a node say what it is, which is the
difference between asking for a quantum and being given one.

### 13.2 What it binds, and at which level

`libpipewire-0.3.so.0`, by soname, through `java.lang.foreign`, like every
other binding here. Specifically `pw_thread_loop` and `pw_stream`.

**Not the wire protocol.** Speaking it directly would be a second complete
implementation of something that changes, for no gain over the library that
already speaks it and ships on every machine that has the graph at all.

**`pw_stream` rather than `pw_filter` or a raw `pw_node`.** A stream is a node
with the buffer handling done, which is the same trade `pa_stream` is, and the
shape this library already knows: `PulseContext` is a threaded mainloop with a
lock and a condition, and `pw_thread_loop` is the same object with different
spelling. The parts that transfer without redesign are the ones that were
expensive to get right, and they include the arena lifetime rule that says the
loop is stopped before the arena holding its upcall stubs is freed.

**The data path is a pull.** `PW_STREAM_FLAG_MAP_BUFFERS` hands the `process`
callback an mmap'd buffer, which is CoreAudio's shape rather than libpulse's.
So `PcmRingBuffer` sits between the consumer's blocking write and the callback,
exactly as `CoreAudioSink` already does, and the pacing rule holds for the same
reason it holds there.

### 13.3 The POD, which is what makes this hard

PipeWire negotiates formats with serialised objects, and the API for building
them is inline C macros over a `spa_pod_builder`. Panama cannot call a macro.
A binding has to emit the bytes.

This is the one genuinely new risk in the section, and it is the same class of
risk the oracles exist for, so it gets the same answer plus one more. The
oracle prints the layout: `spa_pod` is a size and a type, `spa_pod_object` adds
an object type and an id, `spa_pod_prop` is a key, flags and a value, and
everything is padded to `SPA_POD_ALIGN`. It prints the type and key constants,
which are not small integers: `SPA_TYPE_OBJECT_Format` is 262147 and
`SPA_FORMAT_AUDIO_position` is 65541, and both are exactly the kind of number
nobody notices being wrong.

And then it does what no other oracle here does, because no other one can. It
builds two real PODs with the library's own builder, the format a 5.1 stream
asks for and a latency request, and dumps them as bytes. A Kotlin builder is
correct when it emits the same 184 bytes. That is a unit test with no server in
it, it runs on every row rather than only where a graph is, and it turns a
class of defect that would otherwise surface as a stream that silently
negotiates the wrong thing into a byte comparison.

### 13.4 The sink

`PipeWireSink`, an `AudioSink` like the other four, holding no new rules. The
contract suite it has to pass is the one that already exists, and that is the
whole point of the contract suite.

```
pw_thread_loop_new / _start / _lock / _unlock / _wait / _timed_wait / _signal / _stop / _destroy
pw_context_new / _connect / _destroy
pw_stream_new_simple / _connect / _disconnect / _destroy
pw_stream_set_active / _flush / _get_time_n / _get_state
pw_stream_dequeue_buffer / _queue_buffer / _set_control
pw_properties_new_dict / _free
pw_proxy_add_object_listener / _destroy
```

`pw_properties_new` and `pw_properties_setf` are not in that list and will not
be: both are variadic, a Panama downcall to a variadic function needs a
descriptor per call shape, and a `spa_dict` needs none.

The playhead is `pw_stream_get_time_n`, whose `spa_io_position` carries frames
the graph has actually consumed. The same trap as everywhere else applies and
has the same answer: a graph clock keeps advancing through an underrun, so what
this reports is the count of frames the callback took out of the ring, clamped
the way `PulseSink` clamps to what was written.

### 13.5 The source

The same object with `PW_DIRECTION_INPUT`, which is how `pw_stream` spells the
mirror, and it passes `AudioSourceContract` against a live graph.

It cost one thing in `libsound-core`, and the gap was worth finding.
`PcmRingBuffer` had a blocking write and no blocking read, because until now
every consumer of it was a playback path: the device reads and cannot wait, the
consumer writes and must. Capture puts the device on the other side, so the
rule turns out not to be about reading or writing at all. It is about which
side the device is on, and `readFully` is the half that was missing.

Recording one application is a property here where it is
`pa_stream_set_monitor_stream` on the other side: a capture stream naming
another node in `target.object` is linked to that node's output rather than to a
device, which was confirmed by reading the links on a live graph rather than by
hearing audio: the links were read on a live graph and the recorder's input
ports were joined to the application's output ports rather than to the sink's
monitor. The shipped test does test by ear and does discriminate, by aiming at a
silent application while a loud one plays into the same sink. Section 13.8 says
how the id is resolved and why it is checked first.

### 13.6 Latency, said once and kept

`node.latency` as `<quantum>/<rate>` in the properties at connect. That is what
is built and what `Capability.LOW_LATENCY` rests on.

`SPA_PARAM_Latency` through `pw_stream_update_params` was written here as though
it were part of the same sentence, and it is not built: the object is encoded and
byte-checked against the oracle's dump, and nothing sends it. The two do
different jobs, which is why leaving it out costs nothing yet. The property is
what this node asks the graph for. The parameter is what this node would tell
the graph about latency of its own, for the graph to add up along a chain, and
this node adds none that the graph cannot already see.

What this changes against 4.3 is not the number a client gets, which the shim
already shortens correctly. It is that the number is the node's own. A consumer
reading `latencyNanos` gets the graph's answer for this node rather than the
shim's translation of a request it made on the client's behalf, and
`Capability.TOTAL_LATENCY` stays present because the graph reports the whole
path.

### 13.7 What stops being refused

`PcmEncoding.F64LE` opens. `AudioFormat.layout` carries the eight positions
listed in 13.1 and `accepts` stops answering false for them. Nothing in
`libsound-core` changes to allow it, which is the test of whether the contracts
were drawn in the right place: a backend that can do more says so through
`acceptedEncodings` and `CHANNEL_PLACEMENT`, and a consumer that asked before
opening gets a different answer on a different backend without knowing why.

`PA_SAMPLE_S24LE` is the one row of 13.1's table that is not simply a gain. A
packed 24-bit encoding has no `PcmEncoding` today, deliberately, because FFmpeg
has no 24-bit sample format to produce one from. It stays out until something
produces it.

### 13.8 The graph, and who does the placing

This is what section 1 means by clean, and it is smaller than it sounds.

A `pw_stream` with `PW_STREAM_FLAG_AUTOCONNECT` is an ordinary node, and the
session manager places it the way it places every other. A person who wants
EasyEffects between that node and their speakers routes it there, in their own
tools, exactly as they would route anything else. The library's job is to be
routable, not to know.

Where a consumer has already chosen, `SinkConfig.device` becomes
`PW_KEY_TARGET_OBJECT` rather than a device name in a connect call. Same
meaning, one property.

**A device list is small and is in. Arbitrary port links are not.**

An earlier draft of this paragraph excluded both together, and they are not the
same size. A registry global arrives carrying the object's whole property dict,
so listing the audio nodes is a filter over an event stream and needs no method
call at all: two hundred lines, and without it this backend could never stand
first. Port-level links are the thing that is a second interface the size of
`VolumeMixer`, and `VolumeMixer` already covers the level a mixer needs, which
is what is playing, how loud, on which device, and moving it.

So the registry is here, with a connection of its own, for the reason the
libpulse mixer takes a second one: a stream of every object on the machine has
no business on the socket carrying audio timing.

**Two things are behind a bind, and both are now built.** Neither is on the
global's own property dict, so neither could be answered by listening alone.

Which device is default is not a property of the graph at all: the session
manager writes it into a metadata object. So that object is bound, chosen out of
the several the graph carries by `metadata.name`, and its property events fill in
the default sink and source. A device's own volume is a parameter of its node,
so each audio node is bound and subscribed to that one parameter, which is why a
slider somebody else moved arrives as an event rather than at the next re-read.
Both scales agree with the libpulse side without conversion, measured: a sink set
to half through the pulse protocol reads 0.125 on both, because the protocol's
own scale is the cube root of amplitude.

`pw_registry_bind` is a macro like `pw_core_get_registry`, so it is the same walk
of the proxy's method table, and every offset comes from the oracle.

**Recording one application is a property here rather than a call**, and that
changes what going wrong looks like. `pa_stream_set_monitor_stream` fails when it
fails; a `target.object` the graph does not recognise is a stream that connects
to whatever was going anyway, which for a capture is a microphone in a room. So
the id is resolved before it is used. It arrives from `VolumeMixer`, which speaks
the pulse protocol whichever backend is playing, and its number is the object
serial, measured against `pactl` on pipewire-pulse 1.6.8. A serial is monotonic
and never reused, so it names the application meant or names nothing, and one the
registry cannot find is refused rather than left to the graph to resolve.

**A connect waits for an answer rather than for a clock.** A registry global is
an event, so nothing a client calls returns to say the graph has finished
describing itself. What does is a sync on the core, answered after everything the
server had already queued. Two of them: the first covers the burst of globals,
the second covers what binding the metadata object inside that burst asked for.
Measured to be load bearing rather than assumed, on a graph with one sink, where
the default read after the first sync was null and after the second was the sink.

**Creating devices is not in this section.** `createVirtualSink` is
`create_object` on the core now, with a lifetime tied to the connection rather
than to the server, and 13.11 says what that buys. What this section says is
only that a null sink is a system-wide object with a restore obligation
attached, which is a different subject from a stream this process plays
through.

### 13.9 Selection, and what happens on a machine without a graph

`AudioBackends.open` has a rung above the existing one, and it is the default:

```
PipeWire native -> libpulse -> JavaSound
```

The middle rung stays and stays supported, for two machines. One runs real
PulseAudio, where the native path has nothing to connect to. The other runs
PipeWire without `pipewire-pulse` installed, which is the case that gets
nothing from this library today.

Two rules about the promotion, and they are the same rule twice. The native
backend goes first only once it passes every suite the libpulse one passes, on
the same CI rows, and `AudioBackend.name` says which won so a bug report starts
with the answer. A property forces either, because the first person to hit a
difference between them needs to be able to tell which side it is on without
rebuilding.

**The distance left, measured rather than estimated.** Against
`pipewire-pulse` 1.6.8 on the same graph, the two backends now report the same
capability set with one exception:

| | libpulse | native |
|---|---|---|
| `STREAM_VOLUME`, `STREAM_IDENTITY`, `DEVICE_POSITION`, `UNDERRUN_COUNT` | yes | yes |
| `DEVICE_ENUMERATION`, `DEVICE_SELECTION`, `DEVICE_EVENTS`, `DEVICE_VOLUME` | yes | yes |
| `LOW_LATENCY`, `TOTAL_LATENCY`, `CHANNEL_PLACEMENT`, `CAPTURE` | yes | yes |
| `PER_STREAM_CAPTURE` | yes | yes |
| `SAMPLE_CACHE` | yes | **no** |

The sample cache is a PulseAudio idea with nothing behind it in the graph:
uploading a sound the server answers to by name is a protocol feature, not a
node. Borrowing libpulse for those two calls was considered and rejected,
because it would make the capability depend on `pipewire-pulse` being installed,
and a machine that has the graph and not the shim is one of the two machines the
rung below exists for. So the honest answer is that the native backend has no
sample cache and says so, which is what `Capability` is for.

That made the promotion a trade rather than an upgrade, and taking it as a trade
would have meant removing a working feature from everyone who had it. So the
trade was refused rather than accepted: `open` takes an optional set of
capabilities the caller needs, and answers with the first rung that offers them.

```kotlin
AudioBackends.open("Example")                                   // pipewire
AudioBackends.open("Example", setOf(Capability.SAMPLE_CACHE))   // pulse
```

The same question `capabilities` already answered, asked one step earlier, and
it is for the consumer that cannot adapt. One that can should open plainly and
hide what is missing, which is what every other capability here is for. A rung
that opens and turns out to be short is closed again rather than left attached,
and a capability nothing on the machine offers answers null instead of a backend
that was already told it would not do.

The property still pins either and now wins over the request, with the
disagreement logged. A run that pinned a backend and silently got another
measures the wrong thing, which is the one failure a pin exists to prevent.

**One thing had to be fixed before the promotion could be safe**, and it was
invisible while the native backend was reachable only by asking. Loading
libpipewire and starting a thread loop touches no socket, measured, so on a
machine with the library installed and no graph running both succeeded and
`connectOrNull` answered with a backend whose first sink would fail. Harmless
while nothing selected it; not harmless as a rung, because by then the selection
has committed and the rung below is gone. The registry's connect is the test for
a graph now, rather than an extra a backend could do without.

### 13.10 Open questions

| Question | Answer |
|---|---|
| Does a Kotlin POD builder emit the same bytes as `spa_pod_builder`? | **Answered: yes, on the first run, and it stays answered.** The oracle dumps a 5.1 format, a latency request and a Props object, and the tests compare against all three. The reader is checked the same way, against the same dumps, which is what stops the encoder and the decoder agreeing about something they both have wrong. |
| What does `pw_stream_get_time_n` report through an underrun? | Unmeasured, and the sink no longer implies otherwise. Every other backend's clock had this trap and each one needed a different correction, so assume it has one until a stream fed half a second and left alone says otherwise. Nothing depends on the answer: the playhead is the count of frames the callback took out of the ring, which is right whichever way this turns out. |
| Is `PW_STREAM_FLAG_RT_PROCESS` worth taking? | Still open, and now the only half of 4.5 that is. The writing thread asks RealtimeKit here the way it does on the libpulse rung, which is what a caller setting `realtime` was silently not getting. The flag is the other half: it promises the callback runs on the graph's real-time thread, and whether a JVM callback belongs there at all is a different question from whether a JVM write loop does. The answer involves what a garbage collection pause does to the graph rather than to one stream. |
| How much of `PulseBackend` survives? | **Narrowed to one thing.** The device list is the graph's after all and is native now, and so is a device's volume and recording one application. What is left of the backend is the sample cache, which has nothing behind it in the graph at all. |
| Does the mixer need a native half? | **Answered: yes, and built.** See 13.11 for what it covers and for the one thing it does not. |

### 13.11 The mixer's native half

The question 13.10 left open has an answer, and the review that closed it also
showed why the answer is not optional.

`VolumeMixers.open` returned a `PulseMixer` on every Linux machine, whichever
backend is playing. That is fine where `pipewire-pulse` is installed and it is
nothing at all where it is not, and a machine running PipeWire without the shim
is one of the two the rung below `AudioBackends` exists for. So on that machine
today: a backend that plays, and no mixer.

It is also where a capability stopped being honest. The native backend reads
each device's own volume off its node, so `AudioDevice.volume` is filled in, and
`Capability.DEVICE_VOLUME` says the volume can be read **and set**. Setting it is
`VolumeMixer`'s, and on that machine there is no `VolumeMixer`. A settings screen
that asked the capability and drew a slider would draw one that moves nothing.

**What the native half covers.** The graph owns all of it, and each piece is a
mechanism this backend already uses:

| | How |
|---|---|
| `streams()` | The registry already keeps the `Stream/Output/Audio` nodes and their serials. Their properties come with the global; their volume is the same bound-node `SPA_PARAM_Props` a device's is. |
| `setVolume`, `setMuted`, `setDeviceVolume`, `setDeviceMuted` | `pw_node_set_param` with a Props object. The encoder writes one already and the oracle dumps a reference to check it against. |
| `moveTo` | `target.object` on the node, which is what a capture stream is already aimed with. |
| `setDefaultDevice` | `pw_metadata_set_property` on the object this already binds to read the default from. |
| `onStreamsChanged` | The registry's own event stream, filtered the way the device list is. |
| `meter` | A capture stream aimed at the node, which is what per-application capture already builds. |

**What it does not cover, and the reason is not the one an earlier draft gave.**
That draft said cards and virtual devices stay on the pulse protocol and cited
13.8 for it, which was citing a decision as though it were a property of the
graph. Everything on that list is reachable, checked against the installed
headers, and two of the three are now built.

**Virtual devices are built**, and the lifetime question they raised is
answered. `create_object` with the adapter factory and `support.null-audio-sink`
behind it, which is the pair the daemon's own shipped configuration names.
`object.linger` is left unset, so what comes back belongs to the connection that
asked for it. The header describes that key as the one making an object outlive
its client, so leaving it out is what ties the two together. That is a different
lifetime from the module the libpulse mixer loads and it is the shape
`createVirtualSink`'s own obligation wants, since a device left behind is one
somebody finds in their settings and cannot account for.

A device is waited for rather than answered for, and taken back off the graph
if what appears is not what was asked for. An id handed out for a device the
graph accepted and did not build is an id every later call answers false for,
and an id handed out for one that came back narrower is worse: a caller that
asked for a six channel bus finds out by hearing four of its channels vanish.

**The channel count is not what the volume array says it is.** Measured on a
null sink created with six channels. `channelVolumes` carried two entries until
something wrote a volume to it, at which point it became six, while `channelMap`
carried all six from the moment the device appeared. So the map is what a
channel count is read off, and a volume written off the array's length would
have set two channels of that device and left four where they were, which is the
failure the refusal in `setProps` exists to prevent.

**Combining two devices is not built.** It is a module the daemon loads rather
than an object a client creates: the core's method table has no call that loads
one, and the daemon's shipped configuration does not load the module that would
register a factory. On a graph whose owner loaded it by hand there would be a
factory, and finding it by name and checking what it answers is the part that
does not exist here. Nothing is asked, so nothing is refused, and the mixer's
own documentation says so where a consumer would read it.

**Cards, profiles and ports are not built, and the reason is that nothing here
can exercise them.** They are reachable: `PipeWire:Interface:Device` with
`SPA_PARAM_EnumProfile` and `SPA_PARAM_Profile`, ports with `EnumRoute` and
`Route` on the same object, the bind and the subscription and the setter this
already uses on a node, one interface along.

What stops it is the test environment, and stops it for a good reason. The
isolated server this suite runs against has no card and can have none: its
session manager runs with the hardware monitors disabled, which is the whole of
why it is safe to run a suite that reconfigures devices. The only machine with a
card is somebody's desktop, and this suite does not go there. Building profile
switching would mean shipping the most dangerous call in `VolumeMixer` untested,
on the one operation whose own documentation says a card left on a profile
nobody chose is a machine whose speakers have stopped working with nothing on
screen to explain it.

**Which means selection is the reverse of 13.9's.** The native mixer's
capability set is a subset of the pulse one's, short by exactly
`DEVICE_PROFILES`. So the widest goes first, which is the opposite of the
backends, where the native rung offered something the rung below did not and
going direct cost nothing. Going direct here would take a consumer's card panel
away on every machine that has the shim, without that consumer having asked.

`VolumeMixers.open` still takes the same optional set of capabilities
`AudioBackends.open` does, and it is worth being plain about what that does
while one set contains the other: it cannot move the choice on a machine where
the pulse rung opens. What it does is refuse on the machine that has no shim,
where a consumer whose whole feature is a card's profile is told null rather
than handed a mixer whose card list is empty.

That ordering rests on today's coverage rather than on anything permanent. Close
what is left and the two sets are equal, and going direct costs nothing again.

**What is built.** Streams in both directions, their volume and mute, moving
one, each device's volume and mute, choosing the default, virtual devices laid
out with the channel count they were asked for, a level meter, and the three
events a consumer subscribes for, worked out from the one coarse signal the
graph gives.

Every setter waits for the graph's answer rather than for the request to go out,
which is what `VolumeMixer` asks for and what a slider that springs back needs.
A proxy method carries no answer of its own, so the answer is a sync behind the
write: the server replies to one only after everything queued ahead of it, and a
refusal of the write is one of those things, arriving on the core's error event
naming the proxy it was about.

Moving a stream is claimed only where the metadata object is bound. A graph
running without a session manager has nothing to write a target into and nothing
that would act on one, and a device menu is a control not worth drawing where it
cannot work.

A meter is a capture stream aimed at that node, on a loop of its own: a meter's
callback on the registry's loop would hold up the registry's dispatch, so a
mixer with a meter open would stop hearing about the streams it is metering. It
covers playback rows only, because aiming at a row that is itself recording taps
what that row records from, which is the device rather than the row.

**What is left** is one thing, and it is above: cards, profiles and ports, held
up by having nowhere safe to exercise them rather than by anything about the
graph.
