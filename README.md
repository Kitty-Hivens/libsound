<div align="center">
  <h1>libsound</h1>
</div>

<div align="center">

[![License](https://img.shields.io/badge/license-Apache_2.0-86dbd7?style=for-the-badge&logoColor=D9E0EE&labelColor=1E202B)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-22+-BB86FC?style=for-the-badge&logo=openjdk&logoColor=D9E0EE&labelColor=1E202B)](#)
[![Status](https://img.shields.io/badge/pre--1.0-unpublished-86dbce?style=for-the-badge&logoColor=D9E0EE&labelColor=1E202B)](#status)

</div>

<div align="center">
  <h3>Audio output channel and media session for JVM 22+ via Project Panama.</h3>
</div>

---

The audio-control layer between an application and the OS: what this process
plays, what every other process is playing, and what the desktop does about any
of it. A named output stream with system-level volume and device selection, the
mixer surface for everyone else's streams, and a media session the desktop can
see and control. It does **not** decode, resample, or run effects -- that is
[`skinema`](https://github.com/Kitty-Hivens/skinema)'s job, and duplicating it
would be a defect.

For any JVM desktop application that draws its own UI -- whether it wants to be a
first-class citizen of the desktop's audio stack rather than an anonymous client
row in the mixer, or whether it *is* the mixer. A sibling to
[`libtray`](https://github.com/Kitty-Hivens/libtray) and
[`libnotify`](https://github.com/Kitty-Hivens/libnotify): same binding
discipline, pure `java.lang.foreign`, no JNI, no JNA, no GLib on Linux. Unlike
skinema it ships no natives at all -- it binds to the libpulse and libdbus the
system already has.

<details>
  <summary>Modules</summary>

| Artifact | What it is |
|---|---|
| `libsound-core` | Types and contracts. Zero dependencies, Java 17 floor, no Panama. Compile against this without pulling any backend. |
| `libsound-audio` | The sound server: our own output channel and everyone else's streams. PulseAudio and JavaSound today, WASAPI written, CoreAudio planned. |
| `libsound-session` | The media session: publish our own, read and drive everyone else's. MPRIS today, SMTC planned. |

Split so a consumer pays only for what it uses -- MPRIS without libpulse, an
output channel without D-Bus.
</details>

<details>
  <summary>Why the sink contract is written down</summary>

An audio sink's signatures are the easy half. A consumer driving an
audio/video clock from the playhead depends on behaviours no signature states,
and a backend can implement every method correctly while breaking
synchronisation in ways that look like decoder bugs: opening the device paused,
restarting the frame count somewhere other than zero, returning from a write
before the device took the bytes, letting the position drift while stopped.

`AudioSink`'s documentation states each rule and the reason it exists, and
`AudioSinkContract` in the test fixtures is those rules as assertions. A backend
that passes it is a backend a clock can ride on.

Two of them exist because no platform's own counter answers the question the
contract asks -- how many frames have been played since `open`. JavaSound's
`flush` credits the discarded tail as played and jumps the playhead *forward*;
WASAPI's `Reset` restarts the device clock and would take it *back to zero*.
Both are corrected, in opposite directions, so a consumer sees one meaning.
</details>

<details>
  <summary>Capabilities are queried, not discovered by failing</summary>

macOS has no per-application volume. A JavaSound fallback has no stream identity
and no device selection. Foreign session reading needs a private framework on
macOS and does not exist there in public API.

So a backend reports what it can do before a consumer offers it, and a settings
screen asks before drawing a control that could not work.
</details>

<details>
  <summary>Numbers come from an oracle, never from memory</summary>

Struct offsets, vtable slot indices and interface GUIDs are printed by small C
programs in `tools/`, compiled against the real headers and run -- the Windows
one cross-compiled and run under wine. They are transcribed into the ABI tables,
never guessed, and rerunning them is the first step of any version bump.

This is not ceremony. The sibling libraries inferred one struct size instead and
wrote past an allocation on every call through two shipped releases. On this
library the discipline caught a wrong vtable slot on its first use:
`IAudioSessionControl::RegisterAudioSessionNotification` is slot 10, not 8,
because two grouping-parameter methods sit between it and the one before.
Nothing checks a slot index at runtime.
</details>

<details id="status">
  <summary>Status</summary>

**Pre-1.0 and unpublished.** Nothing is on Maven Central yet, so nothing can
depend on this by accident. The API will still shift.

| Area | State |
|---|---|
| Contracts and core | Done. Types, sink and session contracts, ring buffer, pull pump, fake backend, contract suite. |
| Linux audio (libpulse) | Done and exercised. Named stream with a media role, per-stream volume, device enumeration and events, honest playhead. |
| Linux mixer (libpulse) | Done and exercised. Every stream on the machine, its volume, mute and device, with events -- and everything it changes put back. |
| JavaSound fallback | Done and exercised, with its capability set stating exactly what it loses. |
| Windows audio (WASAPI) | Written to the ABI and compiling; **no part of it has been run.** Verification is on real hardware -- see [docs/TESTING.md](docs/TESTING.md). |
| Windows mixer (IAudioSessionManager2) | Not started. |
| Linux session (MPRIS) | Done and exercised. Publishes a player `playerctl` and the desktop can drive, and reads and controls everyone else's. |
| Windows session (SMTC) | Not started. |
| macOS audio (CoreAudio) | Not started. The ABI oracle is written and runs on a macOS CI runner, since there is no Mac to run it on here. |
| macOS mixer | Will not exist: the platform has no per-application volume in any public API, so [`AudioMixers.open`](libsound-audio/src/main/kotlin/dev/hivens/libsound/audio/AudioMixers.kt) answers null there rather than pretending. |

Verified against a live PipeWire server through `pipewire-pulse`: both Linux
backends pass the same contract suite, the mixer round-trips volume, mute and
routing against real streams, and the stream is visible in
`pactl list sink-inputs` under the name and media role it was given.
</details>

<details>
  <summary>Checking a platform by hand</summary>

A backend that no runner can exercise reaches a release having never executed,
and a hardware suite that skips is the same shade of green as one that passes.
So `./gradlew smoke` is an audible check a person runs: it exercises the same
rules the contract asserts, prints a pass or failure for each, and then asks the
four questions no assertion can answer -- whether a tone was audible, whether
the desktop's mixer shows the stream under our name, whether the slider moves
with our volume, and whether stopping actually silenced the device.

[docs/TESTING.md](docs/TESTING.md) is written for somebody who has never seen
this repository.
</details>

---

> Apache License 2.0 -- fork it, ship it, sell it. Patches welcome but not required.
