# 015 — Apple video streams through a local proxy

**Date:** 2026-11-13 · **Status:** accepted

## Context

Android and Linux give their players the storage URL Takeout redirects to, asking again when
it gets old. Tested with VLCKit 3.0.23: after the 15-minute signed URL expires, VLC retries a
refused range dozens of times and doesn't recover.

## Decision

Keep `StreamProxy`: a loopback HTTP server VLC plays from; it fetches signed URLs from Takeout
(reused up to 10 minutes, fetched again when refused) and passes the bytes through. Neither the
token nor a signed URL reaches VLC.

## Ruled out

- **Giving VLC the signed URL**, as the other platforms do: a long pause or a late seek fails.
