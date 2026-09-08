# Using libsound

What this library is for: playing audio as a named citizen of the desktop,
playing it soon rather than eventually, recording what a machine is hearing or
playing, finding out what any of that can actually do here, quieting everybody
else while something of yours plays, and being a player the desktop knows
about.

Every example below is compiled. They live in `AudioSamples.kt` and
`SessionSamples.kt` in the test sources, and `GuideSamplesTest` fails the build
if this page and those files drift apart -- the same reason the ABI numbers come
from an oracle rather than from memory. An example that no longer compiles reads
exactly like one that does, and the person it misleads is the one who had no
other way to check.

Nothing is on Maven Central yet, so there are no coordinates to give. Build it
from source until there are.

## Playing something

```kotlin
val backend = AudioBackends.open("Example") ?: error("this machine cannot play audio at all")
backend.use {
    val sink = it.createSink(
        SinkConfig(
            applicationName = "Example",
            applicationId = "com.example.player",
            iconName = "audio-x-generic",
            mediaRole = MediaRole.MUSIC,
        ),
    )
    sink.use { channel ->
        channel.open(AudioFormat(48_000, 2))
        // Returns when the device has taken the bytes, not when they
        // were queued. That is what lets a write loop double as a clock.
        channel.write(pcm, 0, pcm.size)
    }
}
```

`AudioBackends.open` returns null only when the JVM cannot play audio at all --
headless, or a container with no device. "No sound server" is not that case: the
JavaSound fallback covers it, and reports through its capabilities what it lost
on the way.

The identity fields are not decoration. A stream with a name, an icon and a role
is a row a user recognises in their mixer and a target an EasyEffects rule can
address. Without them you are an anonymous client labelled with the JVM's
process name.

**`write` blocks until the device has taken the bytes.** That is the whole
pacing mechanism, and it is why a write loop needs no timer. The one escape from
a write that will never drain -- a stopped device, a server that died -- is
`close`, and it is guaranteed to work: every backend is tested for it.

## Asking for a shorter path

The default buffer is 40 ms. A player that never wants to stutter asks for
`RELAXED` and gets the 200 ms this library used to give everybody; a synth or a
game asks for `LOW` or `LOWEST`:

```kotlin
// A profile is a request rather than a promise: the graph's quantum is
// a floor under it, and what the server granted is what latencyNanos
// reports once the stream is open.
val sink = backend.createSink(
    SinkConfig(
        applicationName = "Example",
        latency = LatencyProfile.LOW,
        realtime = true,
    ),
)
sink.open(AudioFormat(48_000, 2))
if (Capability.REALTIME_THREAD !in sink.capabilities) {
    // The system refused the promotion, so the lowest profiles will
    // underrun under load. Worth saying in a settings screen rather
    // than letting a user pick a setting that crackles.
}
return sink
```

**A profile is a request and a ceiling, not a promise.** PipeWire's graph
quantum is a floor: a client asking for less than `clock.quantum` gets the
quantum, and the default of 1024 frames is 21 ms. Measured against
pipewire-pulse on a 48 kHz graph, a 200 ms request came back as 150 ms, 40 ms
as 30, and 10 ms as 16, which was that machine's quantum. The sink logs what it
asked for and what it was granted at open, and `latencyNanos` reports the whole
path at any time.

**`realtime` asks the system for a writing thread that wakes on time.** It is
off by default because the grant is process-wide: RealtimeKit requires a limit
on how long the process may spend at real-time priority before it will hand any
out. A refusal is not fatal, and `Capability.REALTIME_THREAD` is how a consumer
finds out which happened.

Whether the target was too aggressive is a question with an answer:

```kotlin
// A latency target nobody can validate is a setting rather than a
// guarantee. Where the backend cannot count, the number is zero
// forever, which is what the capability tells apart.
if (Capability.UNDERRUN_COUNT !in backend.capabilities) return false
val before = sink.underrunCount()
play()
return sink.underrunCount() > before
```

## Recording

`AudioSource` is `AudioSink` reversed, rule for rule: open starts the device,
`read` blocks until the device has produced the frames, `close` unblocks a read
in flight.

```kotlin
// A capture stream shows in the desktop's privacy indicator, and the
// row that names the application reads exactly these fields.
val source = backend.createSource(
    SourceConfig(
        applicationName = "Example",
        applicationId = "com.example.recorder",
        iconName = "audio-input-microphone",
    ),
)
source.use {
    it.open(AudioFormat(48_000, 1))
    val frame = ByteArray(4_800 * 2)
    // Returns when the microphone has produced every byte, which is
    // what makes a recording loop need no timer of its own.
    it.read(frame, 0, frame.size)
    write(frame)
}
```

The input list and the output list are separate, and a monitor is not a
microphone:

```kotlin
// A monitor is what the machine is playing, offered back as something
// to record. Both are inputs and they are not interchangeable.
return backend.captureDevices().filter { !it.isMonitor }.map { it.id to it.name }
```

One application's output can be recorded on its own, without a virtual device,
without routing, and without that application knowing:

```kotlin
// That application's output and nothing else: not the desktop, not
// whatever else is playing through the same speakers. It is not told.
if (Capability.PER_STREAM_CAPTURE !in backend.capabilities) return null
return backend.createSource(
    SourceConfig(applicationName = "Example", captureStream = stream.id),
)
```

That is worth being plain about, which is why it is behind a capability and
said twice: it reads somebody else's audio.

## The devices themselves

`setVolume` quiets one application. This is the speaker everything plays
through:

```kotlin
// The other half of a mixer: one application quieted, and the device
// everything plays through. Put back by restoreAll, like a stream's.
if (Capability.DEVICE_VOLUME !in mixer.capabilities) return false
return mixer.setDeviceVolume(device.id, 0.5f)
```

A card's profile decides which devices exist at all, which is the bluetooth
headset that sounds good or has a working microphone:

```kotlin
// The bluetooth case: good playback, or the low quality mode that has a
// working microphone. Two profiles of one card.
return mixer.cards().flatMap { card ->
    card.profiles.filter { it.available }.map { card.id to it.name }
}
```

And a device that does not exist in hardware can be created, and other
applications moved into it:

```kotlin
// A device this process owns. close() removes whatever is left, which
// matters more here than for a volume: a virtual sink left behind is a
// device in a user's settings that nothing owns.
val bus = mixer.createVirtualSink("example_voice_bus") ?: return
mixer.streams()
    .filter { it.applicationName == game }
    .forEach { mixer.moveTo(it.id, bus) }
```

## Asking before you draw

```kotlin
if (Capability.DEVICE_SELECTION !in backend.capabilities) {
    // A JavaSound fallback cannot choose a device. Drawing the menu
    // anyway would offer a control that silently does nothing.
    return emptyList()
}
return backend.devices().map { it.id to it.name }
```

Capabilities are queried, never discovered by failing. They are fixed for the
backend's lifetime, so a settings screen may read them once at startup and build
itself from the answer.

This matters more than it looks. macOS has no per-application volume in any
public API; a JavaSound fallback has no stream identity and no device selection;
Windows will not let one application move another's audio to a different device.
A control drawn without asking is a control that does nothing, and the user has
no way to tell that from a bug in your application.

## Quieting everybody else

The reason this library has a mixer at all. Two mechanisms, and the order
matters.

**Prefer the media role.** It is enforced by the session manager and it vanishes
with the stream that asked for it:

```kotlin
// A role is enforced by the session manager and vanishes with the stream
// that asked for it. Direct volume does neither, which is why it is the
// fallback rather than the default: a process that lowers something and
// then crashes leaves a user with quiet audio and nothing to point at.
val duckingWorks = Capability.DUCKS_OTHERS in backend.capabilities
return SinkConfig(
    applicationName = "Example",
    mediaRole = if (duckingWorks) MediaRole.VIDEO else MediaRole.MUSIC,
)
```

**Fall back to volume, and put it back.** A sound server remembers
per-application volume across runs of that application, so this is the one
surface here that writes state outliving your process:

```kotlin
if (Capability.STREAM_CONTROL !in mixer.capabilities) return
mixer.streams()
    .filter { !it.isOurs && it.active }
    .forEach { mixer.setVolume(it.id, it.volume * factor) }
```

```kotlin
// Undoes every change this process made and has not already undone.
// close() calls it too, but a feature that ends with the video should
// not wait for the process to end.
mixer.restoreAll()
```

Together, around the thing you were playing:

```kotlin
val mixer = VolumeMixers.open("Example")
if (mixer == null) {
    // macOS has no per-application volume in any public API. The video
    // still plays; it just plays over the music.
    playVideo()
    return
}
mixer.use {
    duckOthers(it, factor = 0.3f)
    try {
        playVideo()
    } finally {
        stopDucking(it)
    }
}
```

`close` restores whatever is still outstanding, which covers an orderly exit and
does not cover a crash. That is the honest limit of the mechanism, and the
reason the role is the first choice wherever the desktop honours it.

Volume and mute are recorded separately, so restoring a volume you lowered will
not also undo a mute the user set in the meantime.

## Being a player the desktop knows about

```kotlin
val session = MediaSessions.open(
    SessionConfig(
        applicationName = "Example",
        identity = "Example Player",
        desktopEntry = "com.example.player",
        canRaise = true,
    ),
)
// Null is ordinary: no session bus, no backend on this platform yet, or
// another process already owns the name. Audio still plays without it.
return session
```

State goes out whole rather than field by field:

```kotlin
// The whole state at once, not field by field. What the desktop shows is
// one consistent picture, and publishing a title without the playback
// state that goes with it is how a widget ends up showing a new track
// as still paused.
session.publish(
    SessionState(
        playback = PlaybackState.PLAYING,
        metadata = TrackMetadata(
            title = "Bus Stop",
            artists = listOf("Example Artist"),
            album = "Example Album",
            durationMicros = 214_000_000,
            artUrl = "file:///home/example/cover.jpg",
            trackId = "example-track-1",
        ),
        positionMicros = 0,
        canPlay = true,
        canPause = true,
        canGoNext = true,
        canSeek = true,
    ),
)
```

A seek nobody asked you for has to be announced, or the desktop's widget keeps
extrapolating from the position it last knew about:

```kotlin
// A seek the desktop did not ask for has to be announced, or its widget
// keeps extrapolating from the position it last knew about.
session.seeked(positionMicros)
```

And the media keys arrive here:

```kotlin
// The desktop's media keys and panel widgets arrive here. The handler
// runs on a thread the library owns, so hop before touching UI state.
session.onCommand { command ->
    when (command) {
        SessionCommand.Pause -> pause()
        SessionCommand.Play -> resume()
        else -> Unit
    }
}
```

Repeat and shuffle are the two properties standing between a player and every
desktop widget that draws more than transport buttons. They are optional in the
protocol, and that is the whole design of them here:

```kotlin
// Null until there is a queue to repeat, because a widget draws a
// repeat button for a player that publishes LoopStatus and draws none
// for one that does not. Publishing NONE would put a button on a radio
// stream, and pressing it would change nothing.
session.publish(
    SessionState(
        playback = PlaybackState.PLAYING,
        loop = LoopMode.PLAYLIST,
        shuffle = false,
    ),
)
session.onCommand { command ->
    when (command) {
        is SessionCommand.SetLoop -> setLoop(command.loop)
        is SessionCommand.SetShuffle -> setShuffle(command.shuffle)
        else -> Unit
    }
}
```

**Null and `LoopMode.NONE` are different answers.** Null means the player has no
such notion, so the property is not on the interface at all: a desktop asking
for it gets an unknown property, and a widget that checked draws no button.
`NONE` means the player has a queue and is not repeating it. The same holds for
`shuffle`, and for `fullscreen` on the root interface, whose companion
`SessionConfig.canSetFullscreen` says whether the desktop may change it rather
than only read it.

`Raise` and `Quit` arrive as commands too, and only where `canRaise` and
`canQuit` said they would be honoured. A desktop that offers "show the window"
and reaches a player which does nothing with it is the dead button the
capability query exists to prevent.

## Driving everybody else

```kotlin
val reader = SessionReaders.open() ?: return 0
return reader.use {
    it.players()
        // A player publishes whether it will accept being driven.
        // Calling a method it says it does not support is not a bug it
        // has to tolerate.
        .filter { player -> player.canControl && player.playback == PlaybackState.PLAYING }
        .count { player -> it.control(player.id, SessionCommand.Pause) }
}
```

The same properties are readable in that direction, and absent just as often:

```kotlin
return reader.players()
    // A player that publishes no repeat mode has none, so there is
    // nothing to turn off and no button to draw for it. Null is the
    // usual answer: most players on a bus carry neither property.
    .filter { it.canControl && it.loop != null }
    .filter { reader.control(it.id, SessionCommand.SetLoop(LoopMode.NONE)) }
    .map { it.id }
```

Controlling another player is a different kind of act from changing its volume.
A player publishes the methods it is willing to accept and says so through
`canControl`; calling one is taking it up on that offer. Changing a stream's
volume through the mixer asks nobody, which is why that side carries a restore
obligation and this one does not.

## Threads and lifetimes

Every handler -- `onCommand`, `onChange`, `onStreamsChanged`, `onDevicesChanged`
-- runs on a thread the library owns. Hop to your own before touching UI state.
None of them runs on the thread that delivers audio, deliberately: the natural
response to an event is to re-read the thing that changed, and doing that from
inside the sound server's own callback is how a process deadlocks itself.

Everything closeable is `AutoCloseable` and safe to close twice. Closing a
backend closes the sinks it handed out. Closing a mixer restores what it changed.

## What each platform actually gives you

| | Linux | Windows | macOS |
|---|---|---|---|
| Output | libpulse (PulseAudio and PipeWire) | WASAPI | CoreAudio |
| Capture | libpulse, and JavaSound everywhere | **not yet** | **no** -- a bundle, a signature and a live session |
| Latency profiles honoured | yes | **not yet** -- IAudioClient3 | **not yet** |
| Real-time writing thread | yes, through RealtimeKit | **no** | **no** |
| Volume the system shows | yes | yes | **no** -- applied inside the audio unit |
| Stream identity | yes | yes | **no** |
| Device selection and events | yes | yes | yes |
| Read and control other streams | yes | yes | **no** -- no public API exists |
| Watch a stream's level | yes | **not yet** -- see below | **no** |
| Move another stream to a device | yes | **no** | **no** |
| Device volume, cards and ports | yes | **no** | **no** |
| Virtual devices | yes | **no** | **no** |
| Record one application | yes | **no** | **no** |
| Publish a media session | MPRIS | SMTC | MPNowPlayingInfoCenter |
| Read other media sessions | MPRIS | not yet | **no** -- private API only |
| Repeat, shuffle and fullscreen | yes, both directions | **not yet** | **not yet** |

The Windows meter is a toolchain gap rather than a platform one: Windows has
`IAudioMeterInformation`, and mingw-w64 declares the interface without its vtable
or its identifier, so the oracle cannot print either. Peak levels wait for a
toolchain that can, rather than for a GUID written from memory.

Ask the capability rather than reading this table at runtime. It is here to help
you decide what to build, not what to branch on.
