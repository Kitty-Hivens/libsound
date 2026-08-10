# Testing libsound by hand

Two of the three platforms cannot be verified by CI. GitHub's Windows runners
have no output device, so a sink cannot open there at all; its macOS runners do
have one, which is why macOS is checked automatically and Windows is not. The
Windows backend is written against Microsoft's documented COM vtables and an
offset oracle, and it has never executed.

That is what this page is for. It takes about ten minutes and needs no
knowledge of the library.

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
- **macOS** -- there is no per-application mixer; skip that part and answer
  "n/a" for the two questions about it.

Press Enter when it is open, and watch it while the tone plays.

## What to report

Copy the whole output of the run. Then answer the four questions it asks, which
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

Also worth reporting even though nothing asks:

- Anything printed that looks like a stack trace or a `WARN` line.
- How long the whole thing took, if it felt slow to start playing.
- Your Windows version and whether the output device is USB, Bluetooth, HDMI or
  built-in. Bluetooth in particular has its own latency behaviour and it is
  useful to know which one was in the run.

The last line prints `N passed, M failed`. **A run that fails is still a useful
run** -- it is more useful than a passing one, and the output is exactly what is
needed to fix it. Please send it either way.

## If it will not start at all

- `error: invalid source release: 22` or similar means the JDK is too old.
  `java -version` will say which one is being used.
- `UnsatisfiedLinkError`, or a message about native access, means the JVM
  refused a native call. Send the whole message: that is a real finding.
- If it prints `no backend at all` and exits, send that too, along with what the
  Windows sound settings show as the output device.
