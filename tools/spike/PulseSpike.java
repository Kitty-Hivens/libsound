// libsound Phase 0 spike -- throwaway. Proves Panama and libpulse get along,
// and that the sink contract in the plan (section 4) is implementable on
// pa_threaded_mainloop rather than merely plausible.
//
//   java --enable-native-access=ALL-UNNAMED PulseSpike.java
//
// Offsets and constants come from tools/pa-oracle.c against libpulse 17.0.0.
// Nothing here is guessed.

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

public final class PulseSpike {

    // -- oracle output (libpulse 17.0.0) ------------------------------------

    static final int PA_SAMPLE_S16LE = 3;
    static final int PA_CONTEXT_READY = 4, PA_CONTEXT_FAILED = 5, PA_CONTEXT_TERMINATED = 6;
    static final int PA_STREAM_READY = 2, PA_STREAM_FAILED = 3, PA_STREAM_TERMINATED = 4;
    static final int PA_STREAM_START_CORKED = 1;
    static final int PA_STREAM_INTERPOLATE_TIMING = 2;
    static final int PA_STREAM_AUTO_TIMING_UPDATE = 8;
    static final int PA_SEEK_RELATIVE = 0;
    static final int PA_ERR_NODATA = 16;

    static final long SS_FORMAT = 0, SS_RATE = 4, SS_CHANNELS = 8, SS_SIZE = 12;
    static final long BA_MAXLENGTH = 0, BA_TLENGTH = 4, BA_PREBUF = 8, BA_MINREQ = 12, BA_FRAGSIZE = 16, BA_SIZE = 20;

    // -- audio shape --------------------------------------------------------

    static final int RATE = 48_000;
    static final int CHANNELS = 2;
    static final int BYTES_PER_FRAME = 4;      // S16LE stereo
    static final double TONE_HZ = 440.0;
    static final double AMPLITUDE = 0.25;      // not loud enough to hurt

    // -- FFM plumbing -------------------------------------------------------

    static final Linker LINKER = Linker.nativeLinker();
    static Arena stubArena;                    // outlives the mainloop thread by construction
    static SymbolLookup pulse;

    static MemorySegment mainloop = MemorySegment.NULL;
    static MemorySegment context = MemorySegment.NULL;
    static MemorySegment stream = MemorySegment.NULL;

    static volatile boolean abortWrite = false;

    static final List<String> failures = new ArrayList<>();

    // -- handles ------------------------------------------------------------

    static MethodHandle mlNew, mlFree, mlStart, mlStop, mlLock, mlUnlock, mlWait, mlSignal, mlGetApi;
    static MethodHandle ctxNew, ctxSetStateCb, ctxConnect, ctxDisconnect, ctxUnref, ctxGetState, ctxErrno;
    static MethodHandle stNew, stSetStateCb, stSetWriteCb, stConnectPlayback, stWrite, stWritableSize;
    static MethodHandle stCork, stFlush, stGetTime, stGetLatency, stDisconnect, stUnref, stGetState;
    static MethodHandle stGetIndex, stGetBufferAttr, stUpdateTimingInfo;
    static MethodHandle plNew, plSets, plFree;
    static MethodHandle opUnref, strError;

    public static void main(String[] args) throws Throwable {
        System.out.println("libsound Phase 0 -- libpulse via Panama\n");

        stubArena = Arena.ofShared();
        try {
            load();
            bind();
            startMainloop();
            connectContext();
            createStream();

            reportBufferAttr();

            long atOpen = streamTimeUsec();
            check("position starts at zero on open", atOpen == 0,
                    "pa_stream_get_time = " + atOpen + " usec");

            cork(0);
            byte[] tone = sine(2.0);
            MemorySegment toneSeg = Arena.ofAuto().allocate(tone.length);
            MemorySegment.copy(tone, 0, toneSeg, ValueLayout.JAVA_BYTE, 0, tone.length);

            long half = (long) tone.length / 4 * 2;      // ~1s, frame-aligned
            writeAll(toneSeg, 0, half);
            checkPactl();
            checkLatency();
            writeAll(toneSeg, half, tone.length - half);

            checkCorkFreezesPosition();
            checkFlushDoesNotRewind();
            checkBlockedWriteIsBreakable();
        } finally {
            teardown();
        }

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("Phase 0: all checks passed.");
        } else {
            System.out.println("Phase 0: " + failures.size() + " check(s) failed:");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
    }

    // -- loading and binding ------------------------------------------------

    static void load() {
        // By exact soname. The unversioned symlink belongs to the -dev package
        // and majors coexist on real systems -- the skinema rule.
        pulse = SymbolLookup.libraryLookup("libpulse.so.0", stubArena);
        System.out.println("loaded libpulse.so.0");
    }

    static MethodHandle fn(String name, java.lang.foreign.MemoryLayout ret, java.lang.foreign.MemoryLayout... args) {
        MemorySegment sym = pulse.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("libpulse exports no '" + name + "'"));
        FunctionDescriptor fd = (ret == null)
                ? FunctionDescriptor.ofVoid(args)
                : FunctionDescriptor.of(ret, args);
        return LINKER.downcallHandle(sym, fd);
    }

    static void bind() {
        var ADDR = ValueLayout.ADDRESS;
        var I32 = ValueLayout.JAVA_INT;
        var I64 = ValueLayout.JAVA_LONG;

        mlNew = fn("pa_threaded_mainloop_new", ADDR);
        mlFree = fn("pa_threaded_mainloop_free", null, ADDR);
        mlStart = fn("pa_threaded_mainloop_start", I32, ADDR);
        mlStop = fn("pa_threaded_mainloop_stop", null, ADDR);
        mlLock = fn("pa_threaded_mainloop_lock", null, ADDR);
        mlUnlock = fn("pa_threaded_mainloop_unlock", null, ADDR);
        mlWait = fn("pa_threaded_mainloop_wait", null, ADDR);
        mlSignal = fn("pa_threaded_mainloop_signal", null, ADDR, I32);
        mlGetApi = fn("pa_threaded_mainloop_get_api", ADDR, ADDR);

        ctxNew = fn("pa_context_new", ADDR, ADDR, ADDR);
        ctxSetStateCb = fn("pa_context_set_state_callback", null, ADDR, ADDR, ADDR);
        ctxConnect = fn("pa_context_connect", I32, ADDR, ADDR, I32, ADDR);
        ctxDisconnect = fn("pa_context_disconnect", null, ADDR);
        ctxUnref = fn("pa_context_unref", null, ADDR);
        ctxGetState = fn("pa_context_get_state", I32, ADDR);
        ctxErrno = fn("pa_context_errno", I32, ADDR);

        stNew = fn("pa_stream_new_with_proplist", ADDR, ADDR, ADDR, ADDR, ADDR, ADDR);
        stSetStateCb = fn("pa_stream_set_state_callback", null, ADDR, ADDR, ADDR);
        stSetWriteCb = fn("pa_stream_set_write_callback", null, ADDR, ADDR, ADDR);
        stConnectPlayback = fn("pa_stream_connect_playback", I32, ADDR, ADDR, ADDR, I32, ADDR, ADDR);
        stWrite = fn("pa_stream_write", I32, ADDR, ADDR, I64, ADDR, I64, I32);
        stWritableSize = fn("pa_stream_writable_size", I64, ADDR);
        stCork = fn("pa_stream_cork", ADDR, ADDR, I32, ADDR, ADDR);
        // pa_operation* (pa_stream*, pa_stream_success_cb_t, void*) -- three
        // arguments, not two. The first cut dropped userdata and invokeExact
        // refused it, which is the argument for invokeExact over invoke.
        stFlush = fn("pa_stream_flush", ADDR, ADDR, ADDR, ADDR);
        stUpdateTimingInfo = fn("pa_stream_update_timing_info", ADDR, ADDR, ADDR, ADDR);
        stGetTime = fn("pa_stream_get_time", I32, ADDR, ADDR);
        stGetLatency = fn("pa_stream_get_latency", I32, ADDR, ADDR, ADDR);
        stDisconnect = fn("pa_stream_disconnect", I32, ADDR);
        stUnref = fn("pa_stream_unref", null, ADDR);
        stGetState = fn("pa_stream_get_state", I32, ADDR);
        stGetIndex = fn("pa_stream_get_index", I32, ADDR);
        stGetBufferAttr = fn("pa_stream_get_buffer_attr", ADDR, ADDR);

        plNew = fn("pa_proplist_new", ADDR);
        plSets = fn("pa_proplist_sets", I32, ADDR, ADDR, ADDR);
        plFree = fn("pa_proplist_free", null, ADDR);

        opUnref = fn("pa_operation_unref", null, ADDR);
        strError = fn("pa_strerror", ADDR, I32);

        System.out.println("bound " + 34 + " symbols");
    }

    // -- upcalls ------------------------------------------------------------
    //
    // The risk the plan names: these fire on the mainloop thread, so the arena
    // holding the stubs must outlive that thread. stubArena is closed only
    // after pa_threaded_mainloop_free returns, in teardown().

    static void notifyCallback(MemorySegment obj, MemorySegment userdata) {
        // Called with the mainloop lock already held by the mainloop thread.
        try {
            mlSignal.invokeExact(mainloop, 0);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static void requestCallback(MemorySegment s, long nbytes, MemorySegment userdata) {
        try {
            mlSignal.invokeExact(mainloop, 0);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static MemorySegment notifyStub() throws Throwable {
        MethodHandle mh = MethodHandles.lookup().findStatic(PulseSpike.class, "notifyCallback",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class));
        return LINKER.upcallStub(mh,
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS), stubArena);
    }

    static MemorySegment requestStub() throws Throwable {
        MethodHandle mh = MethodHandles.lookup().findStatic(PulseSpike.class, "requestCallback",
                MethodType.methodType(void.class, MemorySegment.class, long.class, MemorySegment.class));
        return LINKER.upcallStub(mh,
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
                stubArena);
    }

    // -- lifecycle ----------------------------------------------------------

    static void startMainloop() throws Throwable {
        mainloop = (MemorySegment) mlNew.invokeExact();
        if (mainloop.address() == 0) throw new IllegalStateException("pa_threaded_mainloop_new failed");
        int rc = (int) mlStart.invokeExact(mainloop);
        if (rc < 0) throw new IllegalStateException("pa_threaded_mainloop_start = " + rc);
        System.out.println("mainloop thread started");
    }

    static void connectContext() throws Throwable {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment api = (MemorySegment) mlGetApi.invokeExact(mainloop);
            MemorySegment name = call.allocateFrom("libsound-spike");
            lock();
            try {
                context = (MemorySegment) ctxNew.invokeExact(api, name);
                if (context.address() == 0) throw new IllegalStateException("pa_context_new failed");
                MemorySegment cb = notifyStub();
                ctxSetStateCb.invokeExact(context, cb, MemorySegment.NULL);
                int rc = (int) ctxConnect.invokeExact(context, MemorySegment.NULL, 0, MemorySegment.NULL);
                if (rc < 0) throw new IllegalStateException("pa_context_connect: " + strerror(errno()));
                while (true) {
                    int st = (int) ctxGetState.invokeExact(context);
                    if (st == PA_CONTEXT_READY) break;
                    if (st == PA_CONTEXT_FAILED || st == PA_CONTEXT_TERMINATED) {
                        throw new IllegalStateException("context state " + st + ": " + strerror(errno()));
                    }
                    mlWait.invokeExact(mainloop);
                }
            } finally {
                unlock();
            }
        }
        System.out.println("context READY");
    }

    static void createStream() throws Throwable {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment ss = call.allocate(SS_SIZE, 4);
            ss.set(ValueLayout.JAVA_INT, SS_FORMAT, PA_SAMPLE_S16LE);
            ss.set(ValueLayout.JAVA_INT, SS_RATE, RATE);
            ss.set(ValueLayout.JAVA_BYTE, SS_CHANNELS, (byte) CHANNELS);

            // The identity the whole library exists to provide: a named,
            // icon-carrying, role-tagged stream that EasyEffects can address.
            MemorySegment pl = (MemorySegment) plNew.invokeExact();
            propSet(call, pl, "application.name", "libsound spike");
            propSet(call, pl, "application.icon_name", "audio-x-generic");
            propSet(call, pl, "media.role", "music");
            propSet(call, pl, "application.id", "dev.hivens.libsound.spike");

            MemorySegment streamName = call.allocateFrom("Phase 0 tone");

            // Bound the buffer so the corked-write test fills it in reasonable
            // time; -1 means "server default" for the fields we do not pin.
            int tlength = (RATE / 5) * BYTES_PER_FRAME;   // 200 ms
            MemorySegment attr = call.allocate(BA_SIZE, 4);
            attr.set(ValueLayout.JAVA_INT, BA_MAXLENGTH, tlength * 2);
            attr.set(ValueLayout.JAVA_INT, BA_TLENGTH, tlength);
            attr.set(ValueLayout.JAVA_INT, BA_PREBUF, -1);
            attr.set(ValueLayout.JAVA_INT, BA_MINREQ, -1);
            attr.set(ValueLayout.JAVA_INT, BA_FRAGSIZE, -1);

            lock();
            try {
                stream = (MemorySegment) stNew.invokeExact(context, streamName, ss, MemorySegment.NULL, pl);
                plFree.invokeExact(pl);
                if (stream.address() == 0) throw new IllegalStateException("pa_stream_new_with_proplist failed");

                stSetStateCb.invokeExact(stream, notifyStub(), MemorySegment.NULL);
                stSetWriteCb.invokeExact(stream, requestStub(), MemorySegment.NULL);

                // START_CORKED so the contract's "position is zero at open" is
                // observable before a single sample moves. The timing pair is
                // what makes framePosition() cheap: AUTO_TIMING_UPDATE keeps
                // the server's numbers coming without a round trip per read,
                // INTERPOLATE_TIMING fills the gaps locally. Without them
                // pa_stream_get_time answers -PA_ERR_NODATA forever.
                int flags = PA_STREAM_START_CORKED | PA_STREAM_INTERPOLATE_TIMING | PA_STREAM_AUTO_TIMING_UPDATE;
                int rc = (int) stConnectPlayback.invokeExact(stream, MemorySegment.NULL, attr,
                        flags, MemorySegment.NULL, MemorySegment.NULL);
                if (rc < 0) throw new IllegalStateException("pa_stream_connect_playback: " + strerror(errno()));
                while (true) {
                    int st = (int) stGetState.invokeExact(stream);
                    if (st == PA_STREAM_READY) break;
                    if (st == PA_STREAM_FAILED || st == PA_STREAM_TERMINATED) {
                        throw new IllegalStateException("stream state " + st + ": " + strerror(errno()));
                    }
                    mlWait.invokeExact(mainloop);
                }
            } finally {
                unlock();
            }
        }
        int idx = 0;
        lock();
        try {
            idx = (int) stGetIndex.invokeExact(stream);
        } finally {
            unlock();
        }
        System.out.println("stream READY, sink-input index " + Integer.toUnsignedString(idx));
        awaitTimingInfo();
    }

    /**
     * AUTO_TIMING_UPDATE requests the first timing block asynchronously, so a
     * get_time immediately after READY can still answer NODATA. Ask once
     * explicitly and poll until the answer is real. A backend does this at open
     * so framePosition() never has to report "no idea" to the clock.
     */
    static void awaitTimingInfo() throws Throwable {
        lock();
        try {
            MemorySegment op = (MemorySegment) stUpdateTimingInfo.invokeExact(
                    stream, MemorySegment.NULL, MemorySegment.NULL);
            if (op.address() != 0) opUnref.invokeExact(op);
        } finally {
            unlock();
        }
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (streamTimeUsec() >= 0) {
                System.out.println("timing info available");
                return;
            }
            Thread.sleep(10);
        }
        check("timing info arrives within a second", false, "pa_stream_get_time still answers NODATA");
    }

    static void teardown() {
        try {
            if (stream.address() != 0) {
                lock();
                try {
                    int rc = (int) stDisconnect.invokeExact(stream);
                    if (rc < 0) System.out.println("pa_stream_disconnect = " + rc);
                } finally {
                    unlock();
                }
                stUnref.invokeExact(stream);
            }
            if (context.address() != 0) {
                lock();
                try {
                    ctxDisconnect.invokeExact(context);
                } finally {
                    unlock();
                }
                ctxUnref.invokeExact(context);
            }
            if (mainloop.address() != 0) {
                mlStop.invokeExact(mainloop);
                mlFree.invokeExact(mainloop);
            }
        } catch (Throwable t) {
            System.out.println("teardown threw: " + t);
        }
        // Only now: the mainloop thread is gone, so no upcall can be in flight
        // and the stubs are unreachable from native code.
        stubArena.close();
        System.out.println("torn down, stub arena closed");
    }

    // -- the checks ---------------------------------------------------------

    static void reportBufferAttr() throws Throwable {
        lock();
        try {
            MemorySegment a = (MemorySegment) stGetBufferAttr.invokeExact(stream);
            MemorySegment v = a.reinterpret(BA_SIZE);
            long tlength = Integer.toUnsignedLong(v.get(ValueLayout.JAVA_INT, BA_TLENGTH));
            long maxlength = Integer.toUnsignedLong(v.get(ValueLayout.JAVA_INT, BA_MAXLENGTH));
            long minreq = Integer.toUnsignedLong(v.get(ValueLayout.JAVA_INT, BA_MINREQ));
            System.out.printf("buffer attr: maxlength=%d tlength=%d (%d ms) minreq=%d%n",
                    maxlength, tlength, tlength * 1000 / (RATE * BYTES_PER_FRAME), minreq);
            // The oracle's offsets are what make these readable at all; nonsense
            // here would mean the table is wrong.
            check("buffer attr reads as sane numbers",
                    tlength > 0 && tlength < 10L * RATE * BYTES_PER_FRAME && maxlength >= tlength,
                    "tlength=" + tlength + " maxlength=" + maxlength);
        } finally {
            unlock();
        }
    }

    static void checkPactl() {
        try {
            Process p = new ProcessBuilder("pactl", "list", "sink-inputs").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            boolean named = out.contains("libsound spike");
            boolean role = out.contains("media.role = \"music\"");
            check("stream carries the name we chose", named, named ? "found in pactl" : "not in pactl output");
            check("stream carries the media role", role, role ? "media.role = music" : "role not visible");
        } catch (Exception e) {
            check("pactl cross-check", false, e.toString());
        }
    }

    static void checkLatency() throws Throwable {
        lock();
        try (Arena call = Arena.ofConfined()) {
            MemorySegment usec = call.allocate(ValueLayout.JAVA_LONG);
            MemorySegment neg = call.allocate(ValueLayout.JAVA_INT);
            int rc = (int) stGetLatency.invokeExact(stream, usec, neg);
            long l = usec.get(ValueLayout.JAVA_LONG, 0);
            // Sane means "in the buffer's ballpark", not any particular number:
            // a zero would mean the timing info never arrived.
            check("latency is reported and plausible", rc == 0 && l > 0 && l < 2_000_000,
                    "rc=" + rc + " latency=" + l + " usec");
        } finally {
            unlock();
        }
    }

    static void checkCorkFreezesPosition() throws Throwable {
        cork(1);
        long a = streamTimeUsec();
        Thread.sleep(300);
        long b = streamTimeUsec();
        // The contract needs stop() to freeze the position, not merely to stop
        // sound: skinema's seek handshake reads the playhead after the freeze.
        check("cork freezes the position", b == a,
                "before=" + a + " after 300 ms=" + b + " (delta " + (b - a) + " usec)");
    }

    static void checkFlushDoesNotRewind() throws Throwable {
        long before = streamTimeUsec();
        lock();
        try {
            MemorySegment op = (MemorySegment) stFlush.invokeExact(stream, MemorySegment.NULL, MemorySegment.NULL);
            if (op.address() != 0) opUnref.invokeExact(op);
        } finally {
            unlock();
        }
        Thread.sleep(50);
        long after = streamTimeUsec();
        // Without PA_STREAM_NOT_MONOTONIC libpulse documents get_time as
        // monotonic; this records what it actually does across a flush, which
        // is what the clock's monotonic clamp is sized against.
        check("flush does not rewind the position", after >= before,
                "before=" + before + " after=" + after + " (delta " + (after - before) + " usec)");
    }

    static void checkBlockedWriteIsBreakable() throws Throwable {
        // The watchdog question. skinema breaks a stuck JavaSound write by
        // closing the line, because the block lives inside the JDK. Here the
        // block is our own wait on the mainloop, so it can be broken by
        // signalling -- without destroying the stream.
        abortWrite = false;
        byte[] filler = sine(5.0);              // far more than the 400 ms buffer
        MemorySegment seg = Arena.ofAuto().allocate(filler.length);
        MemorySegment.copy(filler, 0, seg, ValueLayout.JAVA_BYTE, 0, filler.length);

        Thread breaker = new Thread(() -> {
            try {
                Thread.sleep(400);
                abortWrite = true;
                lock();
                try {
                    mlSignal.invokeExact(mainloop, 0);
                } finally {
                    unlock();
                }
            } catch (Throwable t) {
                System.out.println("breaker threw: " + t);
            }
        }, "spike-breaker");
        breaker.start();

        long t0 = System.nanoTime();
        writeAll(seg, 0, filler.length);        // stream is corked: this must block
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        breaker.join();

        check("a write blocked on a corked stream is breakable", abortWrite && elapsedMs < 2_000,
                "returned after " + elapsedMs + " ms without disconnecting the stream");
    }

    // -- helpers ------------------------------------------------------------

    static void writeAll(MemorySegment buf, long offset, long total) throws Throwable {
        long written = 0;
        lock();
        try {
            while (written < total) {
                if (abortWrite) return;
                int st = (int) stGetState.invokeExact(stream);
                if (st != PA_STREAM_READY) return;
                long writable = (long) stWritableSize.invokeExact(stream);
                if (writable == -1L) throw new IllegalStateException("pa_stream_writable_size failed");
                if (writable == 0) {
                    mlWait.invokeExact(mainloop);   // the write callback signals
                    continue;
                }
                long chunk = Math.min(writable, total - written);
                chunk -= chunk % BYTES_PER_FRAME;
                if (chunk == 0) {
                    mlWait.invokeExact(mainloop);
                    continue;
                }
                MemorySegment slice = buf.asSlice(offset + written, chunk);
                int rc = (int) stWrite.invokeExact(stream, slice, chunk, MemorySegment.NULL, 0L, PA_SEEK_RELATIVE);
                if (rc < 0) throw new IllegalStateException("pa_stream_write: " + strerror(errno()));
                written += chunk;
            }
        } finally {
            unlock();
        }
    }

    static void cork(int on) throws Throwable {
        lock();
        try {
            MemorySegment op = (MemorySegment) stCork.invokeExact(stream, on, MemorySegment.NULL, MemorySegment.NULL);
            if (op.address() != 0) opUnref.invokeExact(op);
        } finally {
            unlock();
        }
    }

    static long streamTimeUsec() throws Throwable {
        lock();
        try (Arena call = Arena.ofConfined()) {
            MemorySegment usec = call.allocate(ValueLayout.JAVA_LONG);
            int rc = (int) stGetTime.invokeExact(stream, usec);
            return rc == 0 ? usec.get(ValueLayout.JAVA_LONG, 0) : -1L;
        } finally {
            unlock();
        }
    }

    static void propSet(Arena call, MemorySegment pl, String key, String value) throws Throwable {
        int unused = (int) plSets.invokeExact(pl, call.allocateFrom(key), call.allocateFrom(value));
    }

    static int errno() throws Throwable {
        return (int) ctxErrno.invokeExact(context);
    }

    static String strerror(int e) throws Throwable {
        MemorySegment p = (MemorySegment) strError.invokeExact(e);
        return p.address() == 0 ? ("errno " + e) : p.reinterpret(Long.MAX_VALUE).getString(0);
    }

    static void lock() throws Throwable {
        mlLock.invokeExact(mainloop);
    }

    static void unlock() throws Throwable {
        mlUnlock.invokeExact(mainloop);
    }

    static byte[] sine(double seconds) {
        int frames = (int) (RATE * seconds);
        byte[] out = new byte[frames * BYTES_PER_FRAME];
        for (int i = 0; i < frames; i++) {
            short s = (short) (Math.sin(2 * Math.PI * TONE_HZ * i / RATE) * AMPLITUDE * Short.MAX_VALUE);
            int o = i * BYTES_PER_FRAME;
            out[o] = (byte) (s & 0xFF);
            out[o + 1] = (byte) ((s >> 8) & 0xFF);
            out[o + 2] = out[o];
            out[o + 3] = out[o + 1];
        }
        return out;
    }

    static void check(String name, boolean ok, String detail) {
        System.out.printf("  [%s] %s -- %s%n", ok ? "PASS" : "FAIL", name, detail);
        if (!ok) failures.add(name + " (" + detail + ")");
    }
}
