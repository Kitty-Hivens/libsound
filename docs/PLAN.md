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

Three things, in this order:

1. **Low latency.** Audio that arrives when it should. A keypress heard in
   single-digit milliseconds, not in a fifth of a second.
2. **PipeWire, properly.** Not "it works through the compatibility layer", but
   asking the graph for what a client actually needs and getting it.
3. **MPRIS.** A player the desktop drives, and a reader that drives everyone
   else's.

Everything else in this document serves one of those three or follows from
them.

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
| Processing | The decorator rules are written down and asserted by a fixture. The module itself is still unwritten. |
| Publication | Nothing on Maven Central. |

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
about what a wrapper owes. Four rules, added to `AudioSink`'s documentation and
asserted by a fixture any decorator can extend:

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

**PipeWire natively** is deliberately excluded from this plan. Speaking the
protocol directly rather than through `pipewire-pulse` would expose the node
graph, arbitrary port links and per-node latency, and it is a second complete
binding for a benefit the properties in section 4.4 already deliver most of. It
gets written when a feature needs it.

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

`AudioFormat` counts channels and does not name them, so anything past stereo is
a guess about ordering. A channel map beside the count, defaulting to the
conventional order for the channel count, keeps every current caller working.

### 9.3 Sample formats

`PcmEncoding` carries `S16LE` and `F32LE`. `S24LE` and `S32LE` are accepted by
every backend here and are what a capture path at higher bit depth produces.

### 9.4 Presentation time

`framePosition` answers frames played. A video pacer wants to know when a frame
about to be written will be heard, which is that plus the total latency of
section 4.7. Exposing it as one call removes the arithmetic from every consumer,
and removes the chance of each getting it differently wrong.

---

## 10. Non-goals

**Decoding, resampling and effects in core.** skinema decodes and converts.
libsound takes frames that are already frames. The processing module of section
5.5 is opt-in, separately published, and depends on `libsound-core` only.

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
| Where does the processing module live? | 5.5 | **Still open.** The rules and the fixture are in `libsound-core`, so either answer stays available. |
| Does `streams()` returning both directions break a consumer badly enough to warrant a separate call? | 5.3 | **Answered: no.** It returns both, rows carry a direction, and stream ids now name the facility they came from, because a sink input and a source output can hold the same index at once. |

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

**5. The processing seam.** Cheap once capture has proved the shape twice. The
contract rules are most of the work and the reference filters are the rest.

**6. The numbers.** Refinement, each item small and independent.

**7. Hardware.** Waits on a person with a Windows machine and gates none of the
above. `docs/TESTING.md` is what that person reads.
