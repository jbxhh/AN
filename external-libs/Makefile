# Native libraries for ANONERO. Requires docker (or CONTAINER=podman).
#
#   make            all three targets
#   make android    arm64-v8a + armeabi-v7a (what the APK needs)
#   make linux      linux-x86_64 + libanonero.so (desktop)

LIBANON    = linux-x86_64/libanonero.so
NPROC     ?= $(shell nproc)
CONTAINER ?= docker
# Pinned to a digest for reproducibility; bump deliberately.
BASE              ?= debian@sha256:6ebd97fa83deb272194a2cf015b3d26a4d538e9ad3a7a79d544c8af5b0a01443   # debian:bookworm
# Debian snapshot the toolchain is frozen to (time-stable reproducibility).
SNAPSHOT          ?= 20260824T000000Z
SOURCE_DATE_EPOCH ?= 1704067200

.PHONY: all android linux clean purge distclean help

all: android linux
android: arm64-v8a armeabi-v7a include/wallet2_api.h VERSION
linux:   linux-x86_64 $(LIBANON) include/wallet2_api.h VERSION

help:
	@echo "targets: android (arm64-v8a armeabi-v7a), linux (linux-x86_64 + libanonero.so)"
	@echo "engine:  $(CONTAINER)    jobs: $(NPROC)"

arm64-v8a:    ; @$(MAKE) --no-print-directory image TARGET=android64 ABI=$@
armeabi-v7a:  ; @$(MAKE) --no-print-directory image TARGET=android32 ABI=$@
linux-x86_64: ; @$(MAKE) --no-print-directory image TARGET=linux     ABI=$@

arm64-v8a armeabi-v7a linux-x86_64: monero build.sh Containerfile

image:
	@command -v $(CONTAINER) >/dev/null || { echo "$(CONTAINER) not found"; exit 1; }
	@echo "==> $(TARGET) -> $(ABI)  ($(CONTAINER), -j$(NPROC))"
	-$(CONTAINER) container rm anonero-$(TARGET) -f >/dev/null 2>&1
	$(CONTAINER) build -f Containerfile --build-arg TARGET=$(TARGET) --build-arg NPROC=$(NPROC) \
	    --build-arg BASE=$(BASE) --build-arg SNAPSHOT=$(SNAPSHOT) \
	    --build-arg SOURCE_DATE_EPOCH=$(SOURCE_DATE_EPOCH) -t anonero-$(TARGET) .
	$(CONTAINER) create -it --name anonero-$(TARGET) anonero-$(TARGET) bash >/dev/null
	rm -rf $(ABI) && mkdir -p $(ABI)
	$(CONTAINER) cp anonero-$(TARGET):/opt/out/. $(ABI)/
	-$(CONTAINER) container rm anonero-$(TARGET) -f >/dev/null
	@# docker runs as root; hand the artifacts back to the invoking user
	-@chown -R $${SUDO_UID:-$$(id -u)}:$${SUDO_GID:-$$(id -g)} $(ABI)

# Built inside the archives' own image, so it links against that glibc -- a host build
# would raise the minimum glibc for everyone running the desktop app.
$(LIBANON): linux-x86_64 include/wallet2_api.h ../app/CMakeLists.txt ../app/src/main/cpp/anonero.cpp
	@echo "==> libanonero.so (linux-x86_64)"
	$(CONTAINER) run --rm -v $(CURDIR)/..:/anonero -w /anonero anonero-linux sh -c \
	    'cmake -S app -B external-libs/linux-x86_64/.build -D ANON_ABI=linux-x86_64 \
	           -D CMAKE_BUILD_TYPE=Release && \
	     cmake --build external-libs/linux-x86_64/.build -j$(NPROC)'
	cp linux-x86_64/.build/libanonero.so $@
	-@chown -R $${SUDO_UID:-$$(id -u)}:$${SUDO_GID:-$$(id -g)} linux-x86_64

include:
	mkdir -p include

include/wallet2_api.h: monero include monero/src/wallet/api/wallet2_api.h
	cp monero/src/wallet/api/wallet2_api.h include/wallet2_api.h
	@# ditto
	-@chown -R $${SUDO_UID:-$$(id -u)}:$${SUDO_GID:-$$(id -g)} include

VERSION: monero
	@echo "ANONERO `git -C . rev-parse --short HEAD 2>/dev/null || echo unknown` with monero `git -C monero describe --tags --always 2>/dev/null || echo unknown`" > VERSION
	-@chown $${SUDO_UID:-$$(id -u)}:$${SUDO_GID:-$$(id -g)} VERSION

# Fires when the submodule hasn't been checked out yet.
monero:
	$(error monero submodule not initialised -- run: git -c http.proxy=socks5h://127.0.0.1:9050 submodule update --init --recursive)

clean:
	-rm -rf arm64-v8a armeabi-v7a linux-x86_64 include VERSION

# Also removes images from the pre-refactor Makefile's naming scheme.
purge: clean
	-for i in anonero-android64 anonero-android32 anonero-linux \
	          monero-android-arm64 monero-android-arm32 \
	          monero-android-x86_64 monero-android-x86; do \
	    $(CONTAINER) container rm $$i -f >/dev/null 2>&1; \
	    $(CONTAINER) image rm $$i -f >/dev/null 2>&1; \
	  done; true

distclean: clean
	-for t in android64 android32 linux; do $(CONTAINER) image rm anonero-$$t -f; done
