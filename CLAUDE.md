# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

- `mvn clean package` — compile, run tests, build shaded JAR
- `mvn compile` — fast compile only (skip tests)
- `mvn test` — run all tests
- `mvn test -Dtest=TestClassName` — run a single test class
- `java -jar target/media-server-pro-0.1.1.jar` — run the server
- Only JUnit 5 (no TestNG); test classes end with `Test`
- Java target: 1.8 (source and target level)

## Config

Config is loaded from environment variables via `MediaServerConfig.fromEnvironment()`:
- `MEDIA_HTTP_PORT` (default 18080)
- `MEDIA_RTSP_PORT` (default 1554)
- `MEDIA_RTMP_PORT` (default 11935)
- `MEDIA_WEBRTC_UDP_PORT` (default 18081)
- `MEDIA_RTP_PORT_MIN` / `MEDIA_RTP_PORT_MAX` (default 20000–30000)
- `MEDIA_HLS_STORAGE` (memory or file)
- `MEDIA_HLS_DIRECTORY`
- `MEDIA_WEBRTC_PUBLIC_IP` (default 192.168.3.52)

## Architecture

A Netty-based streaming media server. Target JDK 1.8. Key dependencies: Netty 4.1, BouncyCastle (DTLS), ffmpeg/JavaCV (transcoding), Jackson (JSON API), Logback (logging). The entry point is `com.wenting.mediaserver.MediaServerApplication`.

All bootstraps implement `IServerBootstrap` (start/close/await) and are owned by `MediaServerBootstrap`:

```
MediaServerBootstrap (AutoCloseable)
  ├── HttpBootstrap       — HTTP-FLV, HLS, WebRTC-over-HTTP, JSON API
  ├── RtmpBootstrap       — RTMP ingestion (with RtmpSessionManager)
  ├── RtspBootstrap       — RTSP playback (RTP/UDP + RTP/TCP interleaved)
  └── WebRtcUdpBootstrap  — WebRTC playback over UDP (with WebRtcSessionManager)
```

WebRtcUdpBootstrap protocol stack (bottom-up):
```
UDP → STUN (binding requests) → DTLS (handshake) → SRTP (media encryption)
  ├── IceAgent / IceBindingService — ICE connectivity
  ├── WebRtcDtlsSession / WebRtcBcDtlsEngine — DTLS handshake (BouncyCastle)
  ├── SrtpPacketEncoder — SRTP packet protection
  ├── WebRtcSubscriberSession — per-peer session, SDP negotiation
  └── WebRtcSdpAnswerBuilder — generates SDP answer from subscriber tracks
```

Shared core:
- `core.registry.StreamRegistry` — central stream lookup, wired into every protocol bootstrapper
- `core.publish` — stream ingestion, track payload handling, GOP caching, RTP reorder buffer
- `core.publish.frame` / `core.publish.payload` — codec-specific frame/payload handlers (H264, H265, AAC, G711)
- `core.codec` — protocol codecs (RTP, RTCP, RTSP framing, RTMP chunk stream, HLS, WebRTC)
- `core.remux` — RTP↔RTMP remux, RTSP SDP generation, decoder config records, WebRTC SDP
- `core.track` — AudioTrack / VideoTrack with SDP track mapping
- `core.stats` — traffic counters (in-memory)
- `core.model` — stream keys, SDP structures
- `core.model.sdp` — SDP parser

Protocol packages (`protocol.*`) own their Netty channel initializers, session lifecycle, and codec pipelines.

## Key patterns

- Small `final` classes, constructor injection, no DI framework
- SLF4J / Logback for logging via `LoggerFactory.getLogger(...)`
- Netty pipeline: handlers are `@Sharable` only when stateless
- Test classes match production package layout under `src/test/java`
- Tests use JUnit 5 only; no mocking framework (manual stubs where needed)
- `StreamRegistry` is the central hub connecting publishers to subscribers across all protocols
- Published streams use a dual pipeline: frame-level handlers (`TrackFramePayloadHandler`) for remuxing, and packet-level handlers (`TrackPayloadHandler`) for RTP forwarding
