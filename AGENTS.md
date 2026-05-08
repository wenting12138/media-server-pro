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
