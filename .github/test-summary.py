#!/usr/bin/env python3
"""
Print what each suite actually did, and fail when a suite that was supposed to
run did not.

A green tick says the build did not fail. It does not say a hardware suite ran:
one that skipped every test is the same shade of green as one that passed every
test, which is how skinema shipped a subtitle bundle exporting no symbols.
LIBSOUND_REQUIRE already turns a missing backend into a failure; this closes the
other half, where the backend is present and the suite reached zero tests
anyway -- a filter, a rename, a source set that stopped being compiled.

LIBSOUND_EXPECT names suites, comma separated, by substring of the class name.
Each must report at least one executed test.
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    rows = []
    for path in sorted(glob.glob("*/build/test-results/test/*.xml")):
        root = ET.parse(path).getroot()
        name = (root.get("name") or "?").split(".")[-1]
        tests = int(root.get("tests") or 0)
        bad = int(root.get("failures") or 0) + int(root.get("errors") or 0)
        skipped = int(root.get("skipped") or 0)
        rows.append((name, tests, bad, skipped))

    if not rows:
        print("no test results at all -- did the test task run?")
        return 1

    width = max(len(r[0]) for r in rows)
    print(f"{'suite'.ljust(width)}  {'ran':>5} {'failed':>7} {'skipped':>8}")
    for name, tests, bad, skipped in rows:
        print(f"{name.ljust(width)}  {tests - skipped:5} {bad:7} {skipped:8}")

    total = sum(r[1] for r in rows)
    executed = total - sum(r[3] for r in rows)
    print(f"\n{executed} executed, {sum(r[2] for r in rows)} failed, {sum(r[3] for r in rows)} skipped")

    expected = [e.strip() for e in os.environ.get("LIBSOUND_EXPECT", "").split(",") if e.strip()]
    missing = []
    for want in expected:
        ran = sum(t - s for n, t, _, s in rows if want.lower() in n.lower())
        print(f"expected suite '{want}': {ran} executed")
        if ran == 0:
            missing.append(want)

    if missing:
        print(f"\nFAIL: no test executed in {', '.join(missing)} -- this row exists to run them")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
