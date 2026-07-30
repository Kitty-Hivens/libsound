<div align="center">
  <h1>libsound</h1>
</div>

<div align="center">

[![License](https://img.shields.io/badge/license-Apache_2.0-86dbd7?style=for-the-badge&logoColor=D9E0EE&labelColor=1E202B)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-22+-BB86FC?style=for-the-badge&logo=openjdk&logoColor=D9E0EE&labelColor=1E202B)](#)
[![Platform](https://img.shields.io/badge/Linux%20%7C%20Windows%20%7C%20macOS-planned-86dbce?style=for-the-badge&logoColor=D9E0EE&labelColor=1E202B)](#)

</div>

<div align="center">
  <h3>Audio output channel and media session for JVM 22+ via Project Panama.</h3>
</div>

---

Everything between an application and the OS media services: a named PCM output
stream with system-level volume and device selection, and a media session the
desktop can see and control. It does **not** decode, resample, or run effects --
that is [`skinema`](https://github.com/Kitty-Hivens/skinema)'s job, and
duplicating it would be a defect.

A sibling to [`libtray`](https://github.com/Kitty-Hivens/libtray) and
[`libnotify`](https://github.com/Kitty-Hivens/libnotify): same binding
discipline, pure `java.lang.foreign`, no JNI, no JNA, no GLib on Linux. Unlike
skinema it ships no natives at all -- it binds to the libpulse and libdbus the
system already has.

<details>
  <summary>Modules</summary>

| Artifact | What it is |
|---|---|
| `libsound-core` | Types and contracts. Zero dependencies, Java 17 floor, no Panama. Compile against this without pulling any backend. |
| `libsound-audio` | The output channel: PulseAudio, WASAPI, CoreAudio, with a JavaSound fallback behind the same contract. |
| `libsound-session` | The media session: MPRIS, SMTC, MPNowPlayingInfoCenter. Publish your own, read the desktop's. |

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

`AudioSink`'s documentation states all eight rules and the reason each one
exists, and `AudioSinkContract` in the test fixtures is those rules as
assertions. A backend that passes it is a backend a clock can ride on.
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
  <summary>Status</summary>

**Pre-1.0, and not yet published.** `libsound-core` is complete and tested;
the backends are next.

- **Contracts and core** -- done. Types, sink and session contracts, ring
  buffer, pull pump, fake backend, contract suite.
- **Linux audio** (libpulse) -- feasibility proven end to end: a named stream
  with a media role, honest latency, a playhead that starts at zero, freezes
  under cork and does not rewind across a flush. Implementation next.
- **Linux session** (MPRIS) -- planned.
- **Windows, macOS** -- planned, after Linux has soaked.
</details>

---

> Apache License 2.0 -- fork it, ship it, sell it. Patches welcome but not required.
