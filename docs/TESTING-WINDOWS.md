# Windows by hand: the questions CI cannot answer

[docs/TESTING.md](TESTING.md) is the ten minute audible check and it is the
place to start. This page is the other half, for somebody with a real Windows
machine who is willing to spend longer. Four questions, what each one unblocks,
and the exact command where one exists.

**Why CI cannot answer them.** Wine reimplements the same interfaces with the
same vtable slots and the same interface identifiers, because otherwise real
Windows programs would not run on it, so it is a good oracle for the ABI and CI
uses it for exactly that on every push. It is a poor oracle for behaviour: it
answers `E_NOTIMPL` where Windows does the work, it has no lock screen, and its
audio endpoints are not devices. Everything below is behaviour, which is why a
person is still needed.

**You do not need a compiler.** The oracles in `tools/` answer ABI questions and
are cross compiled on Linux and run under wine already. What is wanted from this
machine is what wine cannot say.

## 1. The contract suites, against real devices

```
set LIBSOUND_REQUIRE=wasapi
gradlew.bat :libsound-audio:test
```

What it adds: the rules a consumer's audio and video clock depends on, asserted
against a real WASAPI implementation instead of wine's. Opening the device,
where the playhead is, what happens across a stop, whether a write returns
before the device took the bytes.

`LIBSOUND_REQUIRE=wasapi` is the important half of the command. Without it a
machine with no usable device skips the whole suite and prints green, which is
indistinguishable from a suite that ran. With it, a skip is a failed build.

What to send: the whole output, and whether the output device is USB, Bluetooth,
HDMI or built in. Bluetooth has its own latency behaviour and it matters which
one was in the run.

## 2. Per session events

Same run as above. Nothing extra to type.

`IAudioSessionControl::RegisterAudioSessionNotification` has never executed
anywhere: wine answers `E_NOTIMPL`, so the path is written and unproven. On real
hardware it either delivers events or it does not.

What to look for in the output: anything naming session notification, and any
`WARN` line at all. A run where the mixer suite passes and says nothing about
notifications is itself an answer worth sending.

## 3. Does the lock screen show the session, and do the media keys arrive?

**No command exists yet.** The audio module has a smoke check a person can watch
and the session module has none.

What it would take: a small program that publishes a session with a title, an
artist and artwork, then waits. A person opens the lock screen, looks, presses
the media keys and says what happened. Roughly forty lines beside the existing
smoke check, and worth writing the moment somebody is on the other end of it.

What it unblocks: the SMTC row in the README stops saying that whether the lock
screen shows it needs a person. Everything about that backend below the lock
screen has executed under wine, so this is the last unknown in it.

## 4. Does `IAudioSessionManager2` enumerate capture sessions?

**No command exists yet, and this is the one worth the most.**

The documentation says a session manager activated on a capture endpoint
enumerates that endpoint's sessions. Nothing anywhere has measured it, and the
answer decides whether Windows can list what is recording at all, which is the
whole Windows half of the capture work in sections 6.2 and 11 of
[docs/PLAN.md](PLAN.md).

What it would take: a probe of the shape `tools/smtc-probe.c` already has.
Activate a session manager on a capture endpoint, enumerate, print what comes
back. It answers yes or no in one run and nothing else can answer it.

The ABI half of the same section, the vtable slots of `IAudioCaptureClient`,
does not need this machine. Wine gives those correctly and the existing oracle
can be extended to print them.

## What to send back

Whole terminal output rather than a summary, including anything that looks like
a stack trace. A run that fails is more useful than a run that passes, and the
output is exactly what is needed to fix it.

Your Windows version, and for anything involving a device, which kind it was.

If a suite skipped rather than ran, say so. A skip that nobody notices is the
failure mode this whole page exists to avoid.
