# 006 — Linux: Qt and Kirigami on the Kotlin/Native core, mpv for playback

**Date:** 2026-10-28 · **Status:** accepted

## Context

A desktop Linux app (and later a terminal one) on the same core as the others.

## Decision

- No JVM: Qt 6 / QML with Kirigami (fits Plasma, runs elsewhere), C++ calling the shared core
  built as a Kotlin/Native library (`libtakeout.so`) through a small C API of JSON calls and
  watches.
- libmpv for all playback: its render API draws into Qt Quick (Wayland-native), libass for
  subtitles, every embedded MKV track works; and seeking past an expired signed URL reopens
  the original and follows a fresh redirect.

## Ruled out

- **Compose Desktop**: needs a JVM.
- **Rust (CXX-Qt, gtk-rs)**: would reach the Kotlin core through the same C library, adding a
  language for nothing.
- **The shared logic rewritten in C++**: two copies to keep in step.
- **VLC**: awkward Qt embedding under Wayland, VLC-Qt unmaintained.
