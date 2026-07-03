package ccl

// Native-library resolution. Go ships as source and has no build/install hook, so — unlike the
// Python (wheel), npm (optionalDeps), and Rust (build.rs) wrappers — libccl is located at runtime:
// an explicit override, a per-version cache, or a one-time download of the release tarball we already
// publish. Resolution is fail-hard: a bad download errors out rather than falling back to a stale lib.

import (
	"archive/tar"
	"compress/gzip"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
)

// defaultLibVersion pins the libccl release fetched when the library isn't already present. Bump it
// in lockstep with a new native-library release. Override at runtime with CCL_LIB_VERSION (matches
// the Rust wrapper's build.rs).
const defaultLibVersion = "v0.1.0-preview1"

const releaseBaseURL = "https://github.com/bloxbean/ccl-bridge/releases/download"

func libVersion() string {
	if v := os.Getenv("CCL_LIB_VERSION"); v != "" {
		return v
	}
	return defaultLibVersion
}

func libFileName() string {
	switch runtime.GOOS {
	case "windows":
		return "libccl.dll"
	case "darwin":
		return "libccl.dylib"
	default:
		return "libccl.so"
	}
}

// platformSlug maps GOOS/GOARCH to the release tarball's platform token.
func platformSlug() (string, error) {
	switch runtime.GOOS {
	case "linux":
		switch runtime.GOARCH {
		case "amd64":
			return "linux-x86_64", nil
		case "arm64":
			return "linux-aarch64", nil
		}
	case "darwin":
		switch runtime.GOARCH {
		case "amd64":
			return "macos-x86_64", nil
		case "arm64":
			return "macos-aarch64", nil
		}
	case "windows":
		if runtime.GOARCH == "amd64" {
			return "windows-x86_64", nil
		}
	}
	return "", fmt.Errorf("no prebuilt libccl for %s/%s; set CCL_LIB_PATH to a local build",
		runtime.GOOS, runtime.GOARCH)
}

// resolveLibPath finds libccl, in order: the CCL_LIB_PATH override (a directory or the file itself),
// the per-version user cache, or a one-time download of the release tarball into that cache.
func resolveLibPath() (string, error) {
	name := libFileName()

	if p := os.Getenv("CCL_LIB_PATH"); p != "" {
		if info, err := os.Stat(p); err == nil && info.IsDir() {
			p = filepath.Join(p, name)
		}
		if fileExists(p) {
			return p, nil
		}
		return "", fmt.Errorf("CCL_LIB_PATH set but %s not found", p)
	}

	cacheRoot, err := os.UserCacheDir()
	if err != nil {
		return "", fmt.Errorf("locate cache dir: %w", err)
	}
	dst := filepath.Join(cacheRoot, "ccl-bridge", libVersion(), name)
	if fileExists(dst) {
		return dst, nil
	}

	if err := downloadLib(dst, name); err != nil {
		return "", fmt.Errorf("fetch libccl %s: %w", libVersion(), err)
	}
	return dst, nil
}

func fileExists(p string) bool {
	info, err := os.Stat(p)
	return err == nil && !info.IsDir()
}

// downloadLib fetches the platform release tarball and extracts `name` to `dst`.
func downloadLib(dst, name string) error {
	slug, err := platformSlug()
	if err != nil {
		return err
	}
	version := libVersion()
	url := fmt.Sprintf("%s/%s/cardano-client-bridge-%s-%s.tar.gz",
		releaseBaseURL, version, version, slug)

	resp, err := http.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("GET %s: HTTP %d", url, resp.StatusCode)
	}
	return extractLib(resp.Body, name, dst)
}

// extractLib reads a gzip'd tarball from r and writes the entry named `name` to `dst`, publishing it
// atomically (temp file + rename) so concurrent or interrupted downloads never leave a partial lib.
func extractLib(r io.Reader, name, dst string) error {
	gz, err := gzip.NewReader(r)
	if err != nil {
		return err
	}
	defer gz.Close()

	tr := tar.NewReader(gz)
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			return fmt.Errorf("%s not found in tarball", name)
		}
		if err != nil {
			return err
		}
		if filepath.Base(hdr.Name) != name {
			continue
		}
		if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
			return err
		}
		tmp, err := os.CreateTemp(filepath.Dir(dst), ".libccl-*")
		if err != nil {
			return err
		}
		defer os.Remove(tmp.Name()) // no-op after a successful rename
		if _, err := io.Copy(tmp, tr); err != nil {
			tmp.Close()
			return err
		}
		if err := tmp.Close(); err != nil {
			return err
		}
		if err := os.Chmod(tmp.Name(), 0o755); err != nil {
			return err
		}
		return os.Rename(tmp.Name(), dst)
	}
}
