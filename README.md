# Weather Log Helper (WLH)

WLH is an internal Android log analysis tool that provides fast scanning and structured results for large log files. It ships as a local daemon plus thin clients (CLI bootstrap, VSCode extension, UltraEdit scripts).

## Repository Structure

```
/
  engine/             Kotlin/JVM daemon (Gradle)
  bootstrap/          wlh.sh + wlh.bat bootstrap scripts
  vscode-extension/   VSCode extension (npm/TypeScript)
  ue18-scripts/       UltraEdit 18 scripts (no build)
  docs/               Usage and troubleshooting docs
```

## Build Responsibilities

Component | Technology | Build Tool | Directory
---|---|---|---
Engine daemon | Kotlin/JVM | Gradle | `engine/`
VSCode extension | TypeScript/Node.js | npm | `vscode-extension/`
UltraEdit 18 scripts | JavaScript | none | `ue18-scripts/`

Build commands must be run from the component’s own directory.

## How to Build

### Engine (wlh-engine)

- Directory: `engine/`
- Command:
  - `./gradlew shadowJar`
- From repo root (equivalent):
  - `./gradlew -p engine shadowJar`

This produces the fat jar under `engine/build/libs/`.

### VSCode Extension

- Directory: `vscode-extension/`
- Commands:
  - `npm install`
  - `npm run build`
- Required settings (VSCode Settings):
  - `wlh.commandPath` (path to `wlh` bootstrap)
  - `wlh.decrypt.jarPath` (path to decrypt jar)

Optional internal packaging uses `vsce` from within `vscode-extension/`:
- `npm install -g @vscode/vsce`
- `vsce package`

### UltraEdit 18 Scripts

No build step. Use the scripts directly from `ue18-scripts/`.
Set `wlhPath` and `decryptJar` in `wlh.config.json` (see `ue18-scripts/config.template.json`).
Available scripts:
- `scan.js` (scan + open `.wlhview.txt` summary)
- `view_results.js` (open summary from existing `.wlhresult`)
- `decrypt.js` (decrypt + open output file)
- `jump.js` (jump to a line selected from `.wlhview.txt`)
- `open_config.js` (open `wlh.config.json`)

## Install (internal)

1. Build the engine jar:
   - `./gradlew -p engine shadowJar`
2. Copy `engine/build/libs/wlh-engine-1.0.0.jar` to `WLH_HOME/engine/wlh-engine.jar` or host it internally and let the bootstrap download it.
3. Put `bootstrap/wlh.sh` (or `bootstrap/wlh.bat`) on PATH as `wlh`.

## Usage

Common commands:
- `wlh status`
- `wlh scan /path/to/log.txt`
- `wlh versions /path/to/log.txt`
- `wlh crashes /path/to/log.txt`
- `wlh decrypt /path/to/log.txt --jar /path/to/dec.jar --timeout 60`
- `wlh adb devices`
- `wlh adb run --serial SERIAL -- shell getprop`

## Scan Result File (.wlhresult)

When a scan completes, the engine writes a result file alongside the log:
- Path: `<log file>.wlhresult`
- Format: JSON serialized `ScanResult`

Schema (high level):
```json
{
  "file": "/abs/path/to/log.txt",
  "versions": [{ "line": 120, "label": "1.0.0 (12345)" }],
  "crashes": [{ "line": 123, "preview": "..." }],
  "generatedAt": "2024-01-01T00:00:00Z"
}
```

Notes:
- The `.wlhresult` file is overwritten on each completed scan.
- Clients can read `.wlhresult` instead of calling result endpoints.
 - Version parsing includes both `Package [name] (...)` blocks (codePath + versionName/versionCode) and `mPackageName='name'` blocks with `VersionName:` / `VersionCode:` lines.
 - Crash parsing includes `FATAL EXCEPTION` (AndroidRuntime lines), `APP CRASHED` (CRASH tag lines), and `ANR in <package>` (next 5 lines).

## Base URL configuration

WLH resolves the update base URL in this order:
1. CLI `--base-url`
2. `WLH_HOME/config/wlh.json` with `updateBaseUrl`

Environment variables are not used for base URL resolution.
`WLH_HOME` is resolved by `--home` or the default `~/.wlh`/`%USERPROFILE%\\.wlh`.

## Common Mistakes

- Running `npm` at the repository root (npm is only for `vscode-extension/`).
- Expecting Gradle to build editor plugins (Gradle is only for `engine/`).
- Mixing build systems across components.
- Assuming environment variables are used for `WLH_HOME` or base URL (they are ignored).

## Developer Workflow Summary

- Build the engine in `engine/` with Gradle.
- Build the VSCode extension in `vscode-extension/` with npm.
- Use `bootstrap/wlh.sh` or `bootstrap/wlh.bat` to run `wlh`.

## Troubleshooting

- Java not found: set `JAVA_HOME` or ensure `java` is on PATH.
- Slow scans: install `rg` (ripgrep) on the machine; WLH will use it automatically when available.
- Large files: VSCode may block extension sync for files above its large-file thresholds. Increase `files.maxMemoryForLargeFilesMB` and `files.maxFileSize` to enable jump actions.
- Updated engine JAR but behavior is unchanged: stop/start the daemon to pick up the new engine.
- Stale daemon: `wlh stop` then `wlh start`.
- Update failures: WLH continues using the last installed engine when possible.

See `docs/usage.md` and `docs/troubleshooting.md` for more detail.
