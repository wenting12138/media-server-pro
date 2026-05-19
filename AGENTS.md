# Repository Guidelines

## Project Structure & Module Organization
Core code lives under `src/main/java/com/wenting/mediaserver`. Packages are split by responsibility: `bootstrap` starts services, `config` loads runtime settings, `protocol` contains HTTP/RTSP listeners, `core` holds codecs, models, registries, and stream publishing logic, and `api` exposes admin endpoints. Runtime resources such as logging config live in `src/main/resources`. Keep generated output in `target/` out of reviews and do not edit it manually. Tests belong in `src/test/java` using the same package layout as production code.

## Build, Test, and Development Commands
Use Maven from the repository root:

- `mvn clean package`: compile, run tests, and build the shaded runnable JAR.
- `mvn test`: run the JUnit 5 test suite only.
- `mvn compile`: validate code changes quickly without packaging.
- `java -jar target/media-server-pro-0.1.1.jar`: run the packaged server locally.

The main entry point is `com.wenting.mediaserver.MediaServerApplication`.

## Coding Style & Naming Conventions
Follow the existing Java style: 4-space indentation, UTF-8 source files, braces on the same line, and concise Javadoc only where behavior is non-obvious. Keep packages lowercase, classes in `PascalCase`, methods and fields in `camelCase`, and constants in `UPPER_SNAKE_CASE`. Prefer small final classes, SLF4J logging, and constructor injection for collaborators. Match package names to protocol or domain areas such as `protocol.rtsp` or `core.registry`.

## Testing Guidelines
JUnit 5 is configured through Maven Surefire. Add tests under `src/test/java/...` with names ending in `Test`, for example `StreamRegistryTest`. Cover protocol parsing, registry behavior, and configuration edge cases before merging network-facing changes. Run `mvn test` before opening a PR; if a change lacks tests, explain why.

## Commit & Pull Request Guidelines
Git history is not available in this workspace, so use clear imperative commit subjects such as `Add RTSP framing decoder`. Keep subjects under 72 characters and group related code changes per commit. PRs should include a short summary, test evidence (`mvn test`, manual RTSP/HTTP checks), linked issues, and sample logs or API output when behavior changes.

## Configuration & Security Tips
Runtime ports are loaded from environment variables such as `MEDIA_HTTP_PORT`, `MEDIA_RTSP_PORT`, `MEDIA_RTMP_PORT`, `MEDIA_RTP_PORT_MIN`, and `MEDIA_RTP_PORT_MAX`. Do not hardcode machine-specific addresses, credentials, or port assumptions in committed code. Use `src/main/resources/logback.xml` for logging changes instead of ad hoc console prints.

## WebRTC Notes
Current WebRTC playback entry points are:

- `POST /webrtc/play`
- `POST /webrtc/stop`
- `GET /webrtc/test`

`/webrtc/play` now returns `data.sessionId` in addition to SDP. Frontends are expected to call `/webrtc/stop` explicitly when stopping playback, instead of relying only on browser disconnect behavior.

Server-side WebRTC session cleanup currently happens through three paths:

- explicit `/webrtc/stop`
- `RTCPeerConnection` terminal state callbacks (`FAILED` / `CLOSED`)
- inbound-idle timeout in `RTCPeerConnection` connection monitor

When changing WebRTC teardown behavior, verify that `DefaultPublishedStream.removeSubscriber(...)` is reached and that `WebRtcSessionManager` count drops to zero.

## WebRTC Media Constraints
The current WebRTC media path is not symmetric for audio and video:

- video: negotiated as `H264`, with the WebRTC playback transform chain able to derive a browser-safe `__webrtc` variant from RTMP/RTSP sources
- audio: currently negotiated as `PCMU/PCMA`, not `Opus`

Important limitation: RTMP audio is typically `AAC`, so the current WebRTC audio path uses in-process `AAC -> G711` conversion at session send time. This is a compatibility-first implementation, not the final shared derived-stream architecture.

Do not change audio SDP negotiation back to `opus` unless the server can actually packetize and send Opus frames end-to-end.

## WebRTC Source Downstream Strategy
For streams published from `protocol.webrtc`, treat the original stream as ingest-oriented and prefer protocol-specific playback variants:

- `RTSP` playback: prefer `stream__webrtc`
- `RTMP` playback: prefer `stream__webrtc`
- `HTTP-FLV` playback: prefer `stream__webrtc`
- `HLS` playback: keep subscribing to the original source stream for video, and attach `stream__hls` as an audio sidecar when present

Current implication:
- WebRTC-published `Opus` audio is transcoded into shared `G711U` on `stream__webrtc`
- `RTSP`/`RTMP`/`HTTP-FLV` can use that derived `G711U` audio path
- `HLS` uses a dedicated `stream__hls` audio sidecar that transcodes WebRTC-source audio into `AAC`, so TS muxing remains `AAC`-only and does not need to understand `Opus` or `G711`

## WebRTC Transcode Architecture
The reusable playback transform pipeline lives under `src/main/java/com/wenting/mediaserver/core/transcode` and is organized as:

- `canonical`: protocol-specific frame normalization
- `engine`: codec transforms
- `orchestrator`: source stream lifecycle and worker scheduling
- `publish`: derived stream publication
- `policy`: passthrough vs transcode decisions

RTMP and RTSP video already share this transform skeleton. If adding shared WebRTC audio transcoding later, prefer extending this pipeline instead of adding more per-session audio conversion logic.

## Recommended Targeted Tests
For WebRTC-related changes, prefer running targeted tests before broader suites:

- `mvn -q "-Dtest=WebRtcPlayHandlerTest,WebRtcStopHandlerTest,WebRtcTestPageHandlerTest,HttpRouterHandlerTest" test`
- `mvn -q "-Dtest=ServerWebRtcPeerSessionTest,WebRtcUdpPacketHandlerTest,RTCPeerConnectionIdleTimeoutTest" test`

If SDP negotiation changes, include a test that inspects the returned answer SDP instead of relying only on manual browser checks.
