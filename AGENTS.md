# WLH Agent Guide

## Repository layout
- `engine/` Kotlin daemon (Gradle, fat jar)
- `bootstrap/` `wlh.sh` + `wlh.bat`
- `vscode-extension/` VSCode client
- `ue18-scripts/` UltraEdit 18 scripts and config template
- `docs/` usage and troubleshooting notes

## Non-negotiable rules
- No hardcoded internal URLs (use `__WLH_BASE_URL__` placeholder)
- Stdout is JSON only (bootstrap and daemon responses)
- Single daemon per `WLH_HOME`
- Never load entire logs into memory
- Preserve decrypt filename rule (`<file>.txt` even if input ends with `.txt`)

## Build commands
- Engine: `./gradlew -p engine shadowJar`
- VSCode extension: `npm install && npm run build` (from `vscode-extension/`)

## Release/update checklist
- Update engine version and rebuild fat jar
- Verify `latest.json` schema and sha256
- Confirm bootstrap downloads and validates engine
- Validate daemon `status` and scan endpoints
- Smoke test CLI and editors
