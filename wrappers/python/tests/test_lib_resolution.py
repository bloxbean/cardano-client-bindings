"""Unit tests for how CclLib locates the native library.

These don't load the library (they only exercise the path-resolution logic), so they run without a
built libccl.
"""
import os

from ccl._ffi import CclLib


def test_explicit_lib_path_wins(monkeypatch):
    monkeypatch.setenv("CCL_LIB_PATH", "/env/dir")
    name = CclLib._lib_filename()
    assert CclLib._resolve_lib_file("/opt/dir") == os.path.join("/opt/dir", name)


def test_env_var_used_when_no_explicit_path(monkeypatch):
    monkeypatch.setenv("CCL_LIB_PATH", "/env/dir")
    # even if a bundled lib exists, an explicit env override takes precedence
    monkeypatch.setattr(os.path, "exists", lambda p: True)
    name = CclLib._lib_filename()
    assert CclLib._resolve_lib_file() == os.path.join("/env/dir", name)


def test_bundled_lib_preferred_when_no_env(monkeypatch):
    monkeypatch.delenv("CCL_LIB_PATH", raising=False)
    monkeypatch.setattr(os.path, "exists", lambda p: True)  # pretend the bundled lib is present
    resolved = CclLib._resolve_lib_file()
    assert resolved.endswith(os.path.join("_libs", CclLib._lib_filename()))


def test_bare_filename_fallback(monkeypatch):
    monkeypatch.delenv("CCL_LIB_PATH", raising=False)
    monkeypatch.setattr(os.path, "exists", lambda p: False)  # nothing bundled
    assert CclLib._resolve_lib_file() == CclLib._lib_filename()
