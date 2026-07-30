# Changelog

All notable changes to libsound will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added
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
