package ccl

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"os"
	"path/filepath"
	"testing"
)

func makeTarGz(t *testing.T, entries map[string][]byte) []byte {
	t.Helper()
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	for name, data := range entries {
		if err := tw.WriteHeader(&tar.Header{Name: name, Mode: 0o644, Size: int64(len(data))}); err != nil {
			t.Fatal(err)
		}
		if _, err := tw.Write(data); err != nil {
			t.Fatal(err)
		}
	}
	if err := tw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := gz.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func TestExtractLib(t *testing.T) {
	want := []byte("fake-native-lib")
	tgz := makeTarGz(t, map[string][]byte{
		"libccl.h":  []byte("header"),
		"libccl.so": want,
	})
	// Extract into a nested dir that doesn't exist yet (must be created).
	dst := filepath.Join(t.TempDir(), "sub", "libccl.so")
	if err := extractLib(bytes.NewReader(tgz), "libccl.so", dst); err != nil {
		t.Fatalf("extractLib: %v", err)
	}
	got, err := os.ReadFile(dst)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("extracted %q, want %q", got, want)
	}
}

func TestExtractLibMissing(t *testing.T) {
	tgz := makeTarGz(t, map[string][]byte{"other.txt": []byte("x")})
	dst := filepath.Join(t.TempDir(), "libccl.so")
	if err := extractLib(bytes.NewReader(tgz), "libccl.so", dst); err == nil {
		t.Fatal("expected an error when the lib is absent from the tarball")
	}
}

func TestResolveLibPathHonorsEnvDir(t *testing.T) {
	// CCL_LIB_PATH pointing at a directory containing the platform lib resolves to that file.
	dir := t.TempDir()
	libFile := filepath.Join(dir, libFileName())
	if err := os.WriteFile(libFile, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	t.Setenv("CCL_LIB_PATH", dir)
	got, err := resolveLibPath()
	if err != nil {
		t.Fatal(err)
	}
	if got != libFile {
		t.Fatalf("resolveLibPath = %q, want %q", got, libFile)
	}
}

func TestPlatformSlugKnown(t *testing.T) {
	// The current test platform must map to a known slug (so a download URL can be built).
	if _, err := platformSlug(); err != nil {
		t.Fatalf("platformSlug for current platform: %v", err)
	}
}
