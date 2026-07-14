"""Isolate lifecycle: thread affinity and use-after-close.

Both behaviours these tests pin down used to end the *process*, not the test — GraalVM aborts, which
Python cannot catch — so each test here is one that could not previously fail politely.

Each test builds its own CclLib rather than using the shared `ccl` fixture: they close it, and one
attaches extra threads to its isolate.
"""

import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

import pytest

from ccl import CclClosedError, CclLib

TESTNET = 1


def test_shared_instance_is_usable_from_many_threads():
    """One CclLib, many threads.

    A GraalVM IsolateThread belongs to the OS thread that created it. This class used to hand the
    creating thread's handle to every caller, so any threaded server (Flask/FastAPI/gunicorn) would
    eventually die with "Must either be at a safepoint or in native mode" — a fatal VM error, not an
    exception. Each thread now attaches its own handle (see CclLib._thread).
    """
    with CclLib() as lib:

        def work(_):
            account = lib.account.create(TESTNET)
            info = lib.address.info(account["base_address"])
            return info["network_id"]

        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(work, range(40)))

    assert len(results) == 40
    assert set(results) == {0}, "every worker should have produced a testnet address"


def test_calls_after_close_raise_instead_of_aborting():
    """Use-after-close must raise, not abort.

    close() tore down the isolate but left the stale handle reachable, so the next call passed it to
    the native side and GraalVM killed the process ("Failed to enter the specified IsolateThread
    context"). Uncatchable, and no traceback pointed at the call.
    """
    lib = CclLib()
    lib.close()

    with pytest.raises(CclClosedError):
        lib.account.create(TESTNET)

    with pytest.raises(CclClosedError):
        lib.version()


def test_close_is_idempotent():
    lib = CclLib()
    lib.close()
    lib.close()  # must not tear the isolate down twice


def test_failed_load_raises_cleanly():
    """A library that won't load must surface the OSError, not an AttributeError from __del__.

    __init__ raised before the isolate fields existed, so __del__ -> close() tripped over the
    half-built object and printed "'CclLib' object has no attribute '_thread'" on top of the real
    error — the first thing a newcomer with a bad CCL_LIB_PATH ever saw.
    """
    with pytest.raises(OSError, match="Failed to load the CCL native library"):
        CclLib(lib_path="/nonexistent-ccl-dir")


def test_use_after_close_does_not_kill_the_interpreter():
    """Belt and braces: prove the process actually survives.

    The regression this guards against was a process abort, and an aborted interpreter can't report
    its own failure — an in-process assertion would simply vanish. So run it in a subprocess and
    check it exits cleanly.
    """
    code = (
        "from ccl import CclLib, CclClosedError\n"
        "lib = CclLib()\n"
        "lib.close()\n"
        "try:\n"
        "    lib.account.create(1)\n"
        "except CclClosedError:\n"
        "    print('raised')\n"
        "print('survived')\n"
    )
    proc = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, timeout=120)

    assert proc.returncode == 0, f"interpreter died (rc={proc.returncode}): {proc.stderr[-400:]}"
    assert "raised" in proc.stdout
    assert "survived" in proc.stdout
