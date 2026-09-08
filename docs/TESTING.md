# Testing libsound by hand

One platform of the three cannot be verified by CI. GitHub's Windows runners
have no output device, so a sink cannot open there at all; its macOS runners do
have one, which is why macOS is checked automatically and Windows is not.

That is what this page is for. It takes about ten minutes and needs no
knowledge of the library.

**What is already covered, so you know what you are adding.** CI runs this same
check on a Windows JVM under wine on every push, which proves the COM plumbing
-- the vtable slots, the interface identifiers, the calls themselves. What it
cannot prove is anything about real hardware: whether sound comes out, what the
latency feels like, how a Bluetooth or HDMI device behaves, and what the row
actually looks like in the real volume mixer. Those are the questions below, and
they are the reason a person is still needed.

`WasapiSinkContractTest` -- the eleven rules a consumer's audio/video clock
depends on -- runs there too, started through the JUnit launcher because Gradle
cannot run on that JVM. It went unexecuted for a while on the assumption that it
needed a Windows machine, which turned out to be false: the Windows JVM was
already there, and so was the launcher.

There is a second page for somebody willing to go further:
[docs/TESTING-WINDOWS.md](TESTING-WINDOWS.md) collects the questions CI cannot
answer at all, with what each one unblocks. This page is the ten minute version
and stands on its own.

## What you need

- **A JDK, version 22 or newer.** [Temurin](https://adoptium.net/) is the usual
  choice. Check with `java -version`; anything below 22 will not start, because
  the library uses a Java feature that did not exist before it.
- **Working speakers or headphones**, with the volume up. Part of the check is
  whether you can hear a tone.
- **Git**, or the repository downloaded as a zip.

Nothing is installed system-wide and nothing is left behind. The check plays a
tone through the normal output device and changes its own volume only. It never
touches the volume of anything else that happens to be playing.

## Running it

```
git clone https://github.com/Kitty-Hivens/libsound
cd libsound
gradlew.bat smoke
```

On macOS or Linux the last line is `./gradlew smoke`.

The first run downloads Gradle and takes a few minutes. Later runs are quick.

The check pauses once and asks you to open the system volume mixer before the
tone starts:

- **Windows** -- press `Win`+`R`, type `sndvol`, press Enter. The window is
  titled "Volume Mixer" and shows one column per application that is playing.
- **macOS** -- there is no per-application mixer in the OS at all; skip that
  part and answer "n/a" for the questions about it.

Press Enter when it is open, and watch it while the tone plays.

## What to report

Copy the whole output of the run. Then answer the five questions it asks, which
are the ones no automatic check can answer:

1. **Did you hear the tone?** A steady, clean 440 Hz note for about three
   seconds. Note it if it was distorted, stuttering, clicking, or wrong in pitch.
2. **Did a row named `libsound smoke check` appear in the mixer?** The name and
   the icon are half of why this library exists, so a row labelled `Java` or
   `javaw.exe` is a defect worth reporting even though sound came out.
3. **Did the tone get quieter, and did the mixer's slider move with it?** The
   volume must be applied by the system, not by us quietly scaling the samples:
   if you hear the change but the slider stays where it was, that is the defect.
4. **Was it silent while the check said it was stopping?** Two seconds of true
   silence. A tone that keeps playing, or a click at either end, is a defect.
5. **Did the list of other applications match the system mixer?** The check
   prints every application the system says is playing. Compare it with the
   mixer window: the same applications should be there, under names you
   recognise. A row that is blank, or named `javaw`, where the mixer shows a
   real name is a defect even though the list is not empty. This part only
   reads -- it never changes anybody else's volume.

Also worth reporting even though nothing asks:

- Anything printed that looks like a stack trace or a `WARN` line.
- How long the whole thing took, if it felt slow to start playing.
- Your Windows version and whether the output device is USB, Bluetooth, HDMI or
  built-in. Bluetooth in particular has its own latency behaviour and it is
  useful to know which one was in the run.

The last line prints `N passed, M failed`. **A run that fails is still a useful
run** -- it is more useful than a passing one, and the output is exactly what is
needed to fix it. Please send it either way.

## Suites that do not run unless you ask for them

Three of them, and all three are about not doing something to your machine
without being asked.

**The JavaSound capture suite records whatever the JVM calls the default
input**, which on an ordinary machine is a microphone in a room. It is skipped
unless a run names it:

```
LIBSOUND_REQUIRE=javasound-capture ./gradlew test
```

CI names it, because a container's default input is a null sink's monitor and
there is nothing there to overhear.

**The libpulse capture suites record a monitor** rather than a microphone, for
the same reason: a monitor is what the machine is playing, which the suite put
there itself. `LIBSOUND_CAPTURE_DEVICE=<source name>` points them at a real
input for a run that means to exercise one, and `pactl list short sources`
lists the names.

**The real-time suite needs RealtimeKit**, which most desktops have and no
hosted runner does. It skips where the service is absent and fails loudly where
a run named it:

```
LIBSOUND_REQUIRE=rtkit ./gradlew test
```

Worth running on a machine with the service, because what it asserts is the
kernel's own answer read back out of `/proc` rather than the daemon's reply.

## If it will not start at all

- `error: invalid source release: 22` or similar means the JDK is too old.
  `java -version` will say which one is being used.
- `UnsatisfiedLinkError`, or a message about native access, means the JVM
  refused a native call. Send the whole message: that is a real finding.
- If it prints `no backend at all` and exits, send that too, along with what the
  Windows sound settings show as the output device.
