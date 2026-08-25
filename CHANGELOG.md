# Changelog

All notable changes to libsound will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added
- `libsound-audio`: the Linux output channel. A PulseAudio backend over
  `pa_threaded_mainloop` -- which is also the PipeWire backend, since
  `pipewire-pulse` speaks the same protocol -- carrying an application name, an
  icon and a media role, so the stream is addressable by an EasyEffects rule
  instead of appearing as an anonymous client. Per-stream volume the desktop's
  mixer shows and follows, device enumeration and selection, and events when the
  default moves.
- `libsound-audio`: the mixer -- every playback stream on the machine, its
  volume, mute and device, with events and a per-stream level meter.
  `VolumeMixer` rather than `AudioMixer`, because everywhere else in audio a
  mixer sums streams into one and this one never touches a sample. It is the
  single surface here that writes state outliving the process, so every change
  is recorded against what it replaced and `close` puts back whatever was not
  put back already.
- `libsound-audio`: the Windows output channel and mixer over WASAPI, and the
  macOS one over CoreAudio. The Windows ABI executes on every push against a
  Windows JVM under wine, which checks the vtable slots and the interface
  identifiers rather than the hardware; the CoreAudio contract suite runs
  against a real output unit on a macOS runner.
- `libsound-session`: the media session, both directions. MPRIS publishes a
  player the desktop drives and reads everyone else's; SMTC and
  MPNowPlayingInfoCenter publish on Windows and macOS. The assertions are made
  through `gdbus` and `playerctl` rather than through our own marshalling,
  which would pass on a message no reader could parse.
- `docs/GUIDE.md`, with every example compiled: the samples live in the test
  sources and the build fails when the page and the code drift apart.
- `flake.nix`, a development shell carrying the JDK, a sound server, a session
  bus and the library search path a store-only filesystem needs. CI enters it
  on every push rather than trusting it.
- `libsound-audio`: the JavaSound fallback, behind the same contract and
  reporting through its capability set exactly what it loses -- no stream
  identity, no system volume, no device selection. Its `flush` credits every
  discarded frame as played, so the sink measures that jump and subtracts it;
  without the compensation a seek would anchor a clock a whole buffer ahead of
  the sound.
- Backend selection: try the sound server, fall back, log which one won once.
  Which backend is running decides what a settings screen may offer, so that one
  line is the answer to most reports of a missing control.
- `AudioTestGate` and `LIBSOUND_REQUIRE`: a named backend that turns out to be
  unavailable fails the build rather than skipping. A skipped hardware suite and
  a passing one look identical in CI otherwise.
- The contract suite carries a class-level timeout. A backend that parks forever
  now fails rather than hanging the build -- found by hanging the build.
- `libsound-core`: the contracts and the types every backend and consumer share.
  `AudioSink` and `AudioBackend` for output, `MediaSession` and `SessionReader`
  for the session, `Capability` / `Capabilities` for what a backend can actually
  do, and `AudioFormat` with the frame arithmetic. No dependencies at all, not
  even a logging facade -- a consumer compiles against the contract without
  pulling a backend, libpulse or D-Bus.
- `AudioSink`'s contract is written down rather than implied. The eight
  behaviours a clock depends on -- open starts the device, open resets the
  position, the write blocks until the device takes the bytes, stop freezes the
  position, flush is valid while stopped, the position need not be monotonic
  across a flush, close unblocks a write in flight, volume is best-effort --
  each carry the reason they exist, and each is asserted by a contract suite
  that every backend has to pass.
- `PcmRingBuffer`, the push/pull bridge, with the two failure directions kept
  separate: a device callback cannot wait, so a read fills the shortfall with
  silence and counts an underrun; a producer can wait and must, so the blocking
  write parks and only the non-blocking one reports a partial accept.
- `PullPump`, driving a `PcmSource` into a sink for consumers that are shaped
  around a callback rather than a push loop.
- Test fixtures: `FakeAudioSink`, a bounded device whose playhead the test
  drives, and `AudioSinkContract`, the executable form of the contract. Shipped
  as fixtures rather than test sources so backend modules and downstream
  adapters run the same assertions.
- `tools/pa-oracle.c`: prints `offsetof` and `sizeof` for the libpulse structs
  the backend will read, so the ABI table is transcribed rather than guessed.
- Java 17 floor for `libsound-core` against 22 for the backends. Panama lives
  only in the backends, and a lower floor for the contract keeps it reusable on
  a runtime that has no `java.lang.foreign`.
