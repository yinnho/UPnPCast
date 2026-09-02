# Roadmap

UPnPCast follows [Semantic Versioning](https://semver.org). Feature work is tracked here; bug fixes and doc updates land continuously. See [CHANGELOG.md](CHANGELOG.md) for what actually shipped.

## Current state (v1.3.0)

- ✅ SSDP discovery hardened: dedicated listener thread, `SO_REUSEADDR` before bind with ephemeral-port fallback, `MulticastLock` acquisition, device dedup by UDN
- ✅ Coroutine-first public API (`DLNACast` facade over an instance-owned `CoreManager` engine)
- ✅ Live playback state (`getPlaybackState`), cached progress with interpolation, volume/mute control
- ✅ `CastOptions`: external subtitles (incl. Samsung), MIME/UPnP class overrides, custom DIDL-Lite
- ✅ Local file casting with built-in HTTP server (Range/seek, correct MIME types, typed errors)
- ✅ Test suite: 92 unit + protocol-level integration tests (in-process fake DLNA renderer)
- ✅ Internals: typed `RemoteDevice`, unified `UpnpHttp` HTTP layer, pure-logic helpers

## Near term (v1.3.x)

- [ ] **GENA event subscription** — subscribe to AVTransport events instead of polling, so remote play/pause/stop is pushed to the app (`StateVariable` callbacks)
- [ ] **Explicit `kotlinx-coroutines-android` dependency** — currently arrives transitively via androidx core-ktx; declare it explicitly so consumers aren't exposed to version drift
- [ ] Restore the Maven Central publishing pipeline (v1.2.0+ currently ships via JitPack only; Central has legacy 1.1.2)

## Later

- [ ] Playlist / queue support (multiple items, next/previous, SetNextAVTransportURI)
- [ ] Media browser support (ContentDirectory browsing of DLNA servers, not just renderers)
- [ ] Multi-device group casting
- [ ] Playback speed control (device-dependent)
- [ ] IPv6 SSDP support
- [ ] Demo app refresh to showcase `CastOptions`, `getPlaybackState` and the polling patterns

## Design principles

- **No breaking public API changes** within a minor version
- **Coroutine-first**: suspend functions, no callbacks
- **Lightweight**: no OkHttp/Gson; NanoHTTPD for local serving only
- **Honest docs**: examples in the README/FAQ must compile against the current release
