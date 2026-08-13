# Using libsound

Four things this library is for: playing audio as a named citizen of the
desktop, finding out what it can actually do here, quieting everybody else
while something of yours plays, and being a player the desktop knows about.

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
val mixer = AudioMixers.open("Example")
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
| Volume the system shows | yes | yes | **no** -- applied inside the audio unit |
| Stream identity | yes | yes | **no** |
| Device selection and events | yes | yes | yes |
| Read and control other streams | yes | yes | **no** -- no public API exists |
| Move another stream to a device | yes | **no** | **no** |
| Publish a media session | MPRIS | SMTC | not yet |
| Read other media sessions | MPRIS | not yet | **no** -- private API only |

Ask the capability rather than reading this table at runtime. It is here to help
you decide what to build, not what to branch on.
