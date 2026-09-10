# Changelog

All notable changes to libsound will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Fixed
- **Anything past stereo was played with its channels in the wrong places on
  Linux.** A `pa_sample_spec` carries a channel count and no positions, and the
  sink connected its stream with a null channel map, so the server applied its
  own default. That default is not a reordering of what a decoder sends: six
  channels resolve to front-left, front-left-of-center, front-center,
  front-right, front-right-of-center, rear-center, with no low frequency channel
  in it at all. A 5.1 stream handed over in FFmpeg's order played its right
  channel out of a front-left-of-center speaker and its low frequency channel
  out of the front right at full level. Wrong from three channels upward, and
  reported by nothing. The map now travels with the format, and what the server
  received is asserted through `pactl` rather than through this library's own
  binding.
- `VolumeMixer.restoreAll` puts back the card profile and the device port. Both
  are set through public methods, both outlive the process, and neither was
  recorded, while the mixer's documentation said every change was. They are also
  the two a user cannot undo from a volume slider: a card carries a profile
  because somebody chose it, often for a reason that is not visible from the
  outside, and a process that replaces one and exits has taken a decision away
  without leaving anything on screen that connects the two.
- `VolumeMixer.createVirtualSink` honours the channel count or refuses it. It
  clamped into a table of two, so a caller asking for a six-channel bus was
  handed a stereo one, with a successful return and a device id, and found out
  by hearing four of its channels vanish.
- `AudioSink.latencyNanos` means the same thing everywhere. Three backends of
  four reported what the client had queued while the contract specified the whole
  path, each with a comment explaining why the contract was wrong. WASAPI now
  asks `GetStreamLatency`, which had been bound and never called, and
  `Capability.TOTAL_LATENCY` says which kind of number a backend gives, so the
  two that cannot report the device's share say so instead of redefining it.

### Added
- **A native PipeWire backend**, reaching the graph with no compatibility layer
  in front of it. A stream in each direction, passing both contract suites
  against a live graph, and it takes what the layer cannot carry: all five
  encodings including the 64-bit float `pa_sample_format_t` has no name for, and
  every one of the forty channel layouts FFmpeg names, four of which the
  libpulse backend refuses. It lists devices and follows them changing, through
  a registry connection of its own, and sets the stream's own volume as a
  control on its node.

  It reports the same capabilities as the libpulse backend on the same graph,
  with one exception. Which device is default comes from the metadata object the
  session manager writes it into, bound and listened to rather than guessed at.
  A device's own volume and mute come from a parameter of its node, subscribed
  rather than polled, so a slider somebody else moved arrives as an event; both
  scales agree with the libpulse side without conversion. One application's
  output can be recorded, aimed by the same id `VolumeMixer` hands out, and an
  id naming nothing on the graph is refused rather than left to connect to
  whatever was going anyway, which for a capture would be a microphone.

  The exception is the sample cache, which is a PulseAudio protocol feature with
  nothing behind it in the graph, and this backend says so rather than
  pretending otherwise.

  Not on the selection path. It goes first once it passes everything the
  libpulse backend passes, and until then `-Dlibsound.backend=pipewire` reaches
  it. A name that matches nothing fails rather than quietly selecting something
  else, because a run that asked for one backend and measured another says
  nothing about either.
- A connection to the graph knows what is on it before it returns. Enumeration
  there is an event stream rather than a call, so nothing returning meant the
  list was complete, and what stood in for that was a fixed wait. A wait long
  enough for a quiet machine is a coin toss on a loaded one, and losing it means
  an empty device list from a backend that says it can enumerate. It now waits
  on a sync, which the graph answers only after everything it had already
  queued.
- `PcmRingBuffer.readFully`, the blocking read the capture direction needs. The
  rule the class was written around turned out not to be about reading or
  writing: it is about which side the device is on, and the side the consumer is
  on can wait and must. Playback had the blocking write and capture had nothing.
- `AudioSink.accepts` and `AudioSink.acceptedEncodings`, with the mirror on
  `AudioSource`. Backends accept different sets and there was no way to find out
  which but to call `open` and catch. `accepts` is true exactly when `open`
  would not throw for want of the shape, and the contract suites assert the pair
  against every encoding on every backend.
- `Capability.CHANNEL_PLACEMENT`, which says whether a sink tells the device
  what each channel is or only how many there are. Present on the libpulse,
  WASAPI and PipeWire backends.
- `Capability.TOTAL_LATENCY`, which says whether `latencyNanos` covers the
  device's own path or only what this client has queued.

### Changed
- The JavaSound fallback takes `U8` and `S32LE` as well as `S16LE`. It always
  could: the accepted set is now read out of the JVM through
  `AudioSystem.isLineSupported`, on the same walk the open makes, rather than
  declared here.
- The WASAPI sink writes a `WAVEFORMATEXTENSIBLE` rather than a plain
  `WAVEFORMATEX`, so all five encodings go across, along with how many of a
  sample's bits carry signal and what each channel is. The plain form can say
  none of the three.
- A layout naming a channel position the platform cannot express is refused at
  `open` rather than carried with that channel missing from the map. Eighteen of
  the thirty-six positions FFmpeg names have an equivalent on both libpulse and
  Windows. The rest are the wide pair, the downmix and binaural pairs, a second
  low frequency channel and the bottom row. `ChannelLayout.unspecified` is the
  documented way to send the same audio and take the platform's own ordering.
- A crash log committed into `libsound-session` is removed from the tree. It
  carried the machine that produced it: the command line, the environment, every
  loaded library and the memory map.

## [0.1.0] - 2026-09-09

### Changed
- The default buffer is 40 ms rather than 200. The old number was measured for
  the JavaSound fallback, where it is the right answer, and then applied to
  three backends that can do far better. A library whose first purpose is
  latency cannot keep it as the default. A consumer that wants the old
  behaviour asks for `LatencyProfile.RELAXED` and gets exactly 200 ms.
- `VolumeMixer.streams()` returns capture streams alongside playback ones,
  because a person looking at their machine sees one picture of it. A panel
  that draws half of that filters on `AudioStream.direction`, and
  `Capability.CAPTURE_ENUMERATION` says whether the capture half can appear at
  all.
- A stream id names the facility it came from. A sink input and a source output
  can carry the same index at the same time, so an index alone would have a
  volume set on one landing on the other.
- `AudioSink.latencyNanos` is specified as the whole path: what is queued here,
  plus the server's share, plus the device's. A consumer estimating the middle
  term would get it wrong differently on every machine.
- A property set on a session nobody is listening to is refused rather than
  answered empty. `CanControl` already said false there, and the specification
  says a set made then has no effect and raises an error, but `Volume` was
  accepted and delivered to no handler. A widget then draws the value it asked
  for over a player that never heard the request, which is the same argument
  this library already makes for refusing `OpenUri` instead of answering it
  politely.

- Commands reach a consumer on a thread of the session's own rather than on the
  one that talks to the bus, which is what both other backends already did and
  said so in the same words. `Quit` is what made it urgent: its natural handler
  closes the session, closing joins the bus thread, and a handler running on
  that thread was a thread waiting for itself, followed by the connection being
  leaked on purpose rather than freed under a caller still inside it.
- Every `Can` property answers false where nothing is listening. The
  specification is explicit that a client meeting `CanControl` false must assume
  no method is implemented and every other `Can` property is false, so
  advertising `CanPlay` true beside it described an object that does not exist.
- `DesktopEntry` is absent rather than blank where a consumer named none. It is
  the fifth property the specification marks optional, and the one a desktop
  appends `.desktop` to when it looks for an icon, so an empty string sent GNOME
  and KDE looking for a file called `.desktop`.
- Two source-incompatible changes, both safe today because nothing is published
  and neither is silent: `SessionCommand` is sealed and gained five members, so
  an exhaustive `when` over it without an `else` stops compiling, and
  `SessionConfig` gained `canSetFullscreen` between `canRaise` and
  `windowHandle`, so a positional construction that reached the window handle
  now fails on the type.

- `Capability.SESSION_LOOP_SHUFFLE`, `SESSION_FULLSCREEN` and
  `SESSION_RAISE_QUIT`, so the three new session surfaces are queried rather
  than discovered by failing, like everything else here. `SessionState` is
  shared by three platforms and only MPRIS carries any of them: a consumer that
  published a repeat mode on Windows was publishing into nothing, and the guide
  told it to ask a capability that did not exist.

- `tools/wasapi-capture-probe.c`, which asks the one question the Windows half
  of capture waits on: whether a session manager activated on a capture endpoint
  enumerates anything. It prints the render endpoint beside it as a control,
  because a machine with nothing recording and an endpoint that cannot be
  enumerated look identical in a single run.
- `:libsound-session:sessionSmoke`, the session's own hand check. A suite can
  prove a message is well formed and cannot prove a desktop drew it, that the
  artwork arrived, or that a key on a keyboard reached the process, which is
  what the SMTC row has been waiting on.
- The vtable slots of `IAudioCaptureClient` and its interface identifier, in
  `tools/wasapi-oracle.c`. Half of an open question in section 6.2 of the plan,
  and the half that never needed a Windows machine.

- `libsound-dsp`, the processing module the decorator rules were written for.
  A gain, a biquad with the usual designers, a limiter and a tap, each a sink
  that wraps a sink, each passing the decorator fixture, and a stack of them
  passing it too. It depends on `libsound-core` alone and carries core's Java
  floor rather than the backends', because it is arithmetic over a buffer and
  needs neither Panama nor a platform library. Nothing that only plays audio
  carries any of it.
- `TapSink`, which is not a filter and is the one most consumers want first. A
  level meter, a spectrum or a waveform needs the samples, and a consumer that
  plays audio already has them: the tap is how they are seen without a second
  copy of the pipeline. It changes nothing, holds nothing, and adds no latency.

- Documentation on every public symbol of every published module, and a build
  that fails without it. Dokka reports what is undocumented and its warnings
  are errors, for the same reason the Kotlin ones are: a warning that does not
  fail piles up unseen behind the build cache. It caught a broken cross
  reference on its first run, which would have rendered as a dead link in the
  javadoc jar. `libsound-dbus` opts out and says why: nothing there is offered
  to anybody, and a reader who reached those types needs the module's own
  documentation rather than a line on each of seventy handles wrapping a
  libdbus call of the same name.

### Fixed
- A seek sent by a desktop is accepted rather than dropped as stale.
  `mpris:trackid` went out escaped into an object path and came back raw, and
  `SetPosition` carries the track the sender believed was playing so a consumer
  can reject a stale one: an escaped path never equals the id it was made from,
  so every scrubber in every media widget moved and nothing happened. The
  escaping is reversed on the way in now, and a track id read off somebody
  else's player goes back to them untouched, because the path they published is
  the one they compare against.
- `players()` no longer undoes what a signal did while it was reading. The
  round trips happen off the lock and the answers were written back under it
  without checking whether anything had arrived in between, so a row could go
  back to before a change a subscriber had already been told about, and the
  next unrelated signal then reported the change undone.
- Signals are resolved and merged off the bus thread. Turning a sender into the
  name it is known by can cost a round trip per player already listed, and the
  connection's own documentation says a handler must not block, because the
  thread it would block is the one the answer has to arrive on. Enumeration
  records each owner as it goes, so the common case is a map lookup.
- A `Properties.Set` carrying fewer arguments than its signature took the
  process down. `dbus_message_iter_init` proves there is a first argument and
  nothing more, and `dbus_message_iter_recurse` on an iterator that has run out
  asserts inside libdbus, which answers a failed assertion with `_dbus_abort`.
  Measured as a SIGABRT that killed the test JVM, reachable by any process on
  the session bus, and present since the session was written. The shape is
  checked before it is read now, here and on the reading side, where an `as`
  arriving where an `a{sv}` was expected reached the same abort through the
  metadata walk. A `Set` whose value is a container that is not a variant is
  refused for the same reason rather than read through: `ssad` used to have its
  first array element taken as the value and acted on.
- A method call this object cannot route is answered rather than dropped.
  Messages are pulled off the connection by hand, so libdbus never runs the
  dispatch that would reply for us, and a call on another object path or an
  interface we do not carry left the caller waiting out its own timeout.
  Walking the object tree from the root is what `busctl` and `gdbus` do, and it
  sat for twenty-five seconds.
- `xesam:trackNumber` goes out as `i`, which is what the metadata specification
  says. As an int64 the key was on the wire and invisible to every reader that
  follows the specification, this library's own included.
- `MinimumRate` and `MaximumRate` describe the rate actually being reported. A
  player publishing 1.5 declared a range of 1.0 to 1.0 around it, which the
  specification forbids and a speed control cannot draw.
- `org.freedesktop.DBus.Peer.GetMachineId` is no longer advertised, because
  nothing answers it. The introspection document says what this object does.
- Setting a property that exists and is read-only answers `PropertyReadOnly`
  rather than `UnknownProperty`, which told a client the property was not there
  at all.
- A dictionary entry whose value the writer declined is given up rather than
  closed. Closed, it left a key with no value, and libdbus answers that with an
  assertion and a core dump rather than with a rejected message.
- A round trip that outlives the caller's patience releases the reply the bus
  thread collects for it afterwards. A peer that has wedged leaked one message
  per call for as long as it stayed wedged.
- The reader waits for its dispatch thread before it frees the bus, which is
  what both other backends already did. The thread reads a player it has just
  seen appear, through handles bound to the arena being freed.

### Added
- Repeat, shuffle and fullscreen over MPRIS, in both directions. `LoopStatus`
  and `Shuffle` are the two properties standing between this player and every
  desktop widget that draws more than transport buttons, and `Fullscreen` is
  the root property a video player is expected to carry. All three are optional
  in the specification and optional here: `SessionState` holds them as null
  until a consumer publishes one, and a session that published none leaves them
  out of `GetAll`, answers an unknown property to a `Get`, refuses a `Set` and
  says the same thing in its introspection, which is where a widget decides
  what to draw. Null is not `LoopMode.NONE`: the first means there is no queue
  to repeat, the second means there is one and it is not repeating, and
  publishing NONE for a radio stream would put a button on it that changes
  nothing. A property that goes away afterwards is announced through the
  invalidated array, the only thing the protocol offers for one that is no
  longer there, and the reader follows it.
- `Raise` and `Quit` arrive as commands, gated on the `canRaise` and `canQuit`
  the session configuration advertised. Both were answered and dropped before,
  so a consumer that claimed either had a control the desktop drew and nothing
  acted on. `SessionConfig.canSetFullscreen` is the third of the same kind, and
  the reader reports all three for other players so a widget can ask before it
  draws.
- Latency that means something on Linux. `LatencyProfile` names four targets
  from 200 ms down to 5, `PA_STREAM_ADJUST_LATENCY` makes `tlength` a latency
  the server shortens its own path to meet rather than a buffer size it may
  ignore, and `minreq` goes to a quarter of the target instead of the server
  default, which is the other half of the number. What was granted is logged at
  open and readable at any time, because a profile is a request and the graph's
  quantum is a floor under it. Measured against pipewire-pulse on a 48 kHz
  graph: 200 ms granted 150, 40 granted 30, 10 granted 16.
- A writing thread that wakes on time, through RealtimeKit on the system bus.
  `RLIMIT_RTTIME` is set first, because the daemon refuses a process that has
  not limited how long it may spend at real-time priority, and the priority
  asked for is five, which is what PulseAudio's own daemon uses. Opt-in through
  `SinkConfig.realtime`: the limit is process wide, so it is not something a
  library takes on behalf of a caller that did not ask. A refusal is reported
  once with its reason and `Capability.REALTIME_THREAD` stays absent.
- `AudioSink.underrunCount`, because a latency target nobody can validate is a
  setting rather than a guarantee. The PulseAudio and CoreAudio backends count
  what the device actually did, and the two that cannot say so through
  `Capability.UNDERRUN_COUNT` rather than through a zero.
- Capture. `AudioSource` mirrors `AudioSink` rule for rule, with its own
  contract suite, its own fake, and the same reasons written down: a read
  blocks until the device has produced the frames, a close unblocks one in
  flight, and frames nobody collected are counted rather than lost quietly.
  PulseAudio and JavaSound implement it, the mixer lists what is listening
  beside what is playing, and capture devices are listed with monitors marked
  as monitors, because a monitor is not a microphone and a list that hides it
  cannot record what the speakers are playing.
- Recording one application's output on its own, with no virtual device and no
  routing: a record stream on the sink's monitor, narrowed to one stream by the
  same call the level meter already used. Behind
  `Capability.PER_STREAM_CAPTURE`, and documented for what it is, which is
  reading another application's audio without telling it.
- The device half of a mixer. Devices carry their own volume, mute, suspended
  state and ports. The mixer sets volume and mute on either direction, moves
  the default, switches ports, and lists and switches card profiles, which is
  the bluetooth headset that sounds good or has a working microphone.
- Virtual and combined sinks, with the obligation that comes with them: what
  this process created it removes, and `restoreAll` unloads modules before it
  restores volumes, because a stream restored onto a device that is about to
  vanish ends up somewhere nobody chose.
- A sample cache: a short sound uploaded once and triggered by name, which is
  the shortest path there is to a click that lands when it is clicked. Whether
  a server keeps one is probed at connect with a silent frame that is uploaded,
  looked up and removed, because it is a fact about the server rather than
  about this library.
- The four rules a decorator owes, in `AudioSink`'s documentation and in a
  fixture any decorator can extend. Processing hangs off that seam and the
  contract said nothing about it before.
- `libsound-dbus`, the bus plumbing both the session and audio modules need.
  Published because a consumer's classpath has to hold it, and fenced behind an
  opt-in marker because it is not an API.
- `tools/rt-oracle.c`, which prints `RLIMIT_RTTIME` and the layout of `struct
  rlimit`, and additions to `tools/pa-oracle.c` for sources, source outputs,
  cards, profiles, ports and the device state.
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
