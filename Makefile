# Cardano Client Bindings — convenience targets for wrapper developers
#
# Usage:
#   make download-lib          Download pre-built library from GitHub Releases
#   make test-python           Run Python wrapper tests
#   make test-go               Run Go wrapper tests
#   make test-rust             Run Rust wrapper tests
#   make test-js               Run JS wrapper tests (requires Bun)
#   make test-c                Run C smoke tests
#   make build                 Build native lib from source (needs GraalVM)
#   make test-all              Build from source + run all wrapper tests

LIB_DIR := core/build/native/nativeCompile

# Detect platform
UNAME_S := $(shell uname -s)
UNAME_M := $(shell uname -m)
ifeq ($(UNAME_S),Darwin)
  LIB_FILE := libccl.dylib
else
  LIB_FILE := libccl.so
endif

.PHONY: download-lib build test-python test-go test-rust test-js test-c test-all clean

download-lib:
	./gradlew :core:downloadNativeLib -PusePrebuilt

build:
	./gradlew :core:nativeCompile

test-python: download-lib
	PYTHONPATH=wrappers/python \
	CCL_LIB_PATH=$(LIB_DIR) \
	DYLD_LIBRARY_PATH=$(LIB_DIR) \
	LD_LIBRARY_PATH=$(LIB_DIR) \
	  python3 -m pytest wrappers/python/tests/ -v \
	    --ignore=wrappers/python/tests/test_quicktx_integration.py \
	    --ignore=wrappers/python/tests/test_new_features_integration.py

test-go: download-lib
	cd wrappers/go/ccl && \
	CGO_CFLAGS="-I../../../$(LIB_DIR)" \
	CGO_LDFLAGS="-L../../../$(LIB_DIR) -lccl" \
	DYLD_LIBRARY_PATH=../../../$(LIB_DIR) \
	LD_LIBRARY_PATH=../../../$(LIB_DIR) \
	  go test -v ./...

test-rust: download-lib
	CCL_LIB_PATH=$(LIB_DIR) \
	DYLD_LIBRARY_PATH=$(LIB_DIR) \
	LD_LIBRARY_PATH=$(LIB_DIR) \
	  cargo test --manifest-path wrappers/rust/Cargo.toml -- --test-threads=1

test-js: download-lib
	CCL_LIB_PATH=$(LIB_DIR) \
	DYLD_LIBRARY_PATH=$(LIB_DIR) \
	LD_LIBRARY_PATH=$(LIB_DIR) \
	  bun test wrappers/js/test/ccl.test.js

test-c: download-lib
	cd native-test && make CCL_LIB_PATH=../$(LIB_DIR) && make test

test-all: build
	./gradlew :native-test:test :wrappers:python:test :wrappers:go:test :wrappers:rust:test :wrappers:js:test

clean:
	./gradlew clean
