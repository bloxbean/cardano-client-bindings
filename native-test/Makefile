NATIVE_DIR ?= ../core/build/native/nativeCompile
CC ?= cc
CFLAGS = -I$(NATIVE_DIR)
LDFLAGS = -L$(NATIVE_DIR) -lccl -Wl,-rpath,$(NATIVE_DIR)

.PHONY: all clean test

all: test_ccl

test_ccl: src/test_ccl.c
	$(CC) $(CFLAGS) -o $@ $< $(LDFLAGS)

test: test_ccl
	DYLD_LIBRARY_PATH=$(NATIVE_DIR) LD_LIBRARY_PATH=$(NATIVE_DIR) ./test_ccl

clean:
	rm -f test_ccl
