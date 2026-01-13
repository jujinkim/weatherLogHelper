# Weather Log Helper (WLH)

WLH is an internal Android log analysis tool that provides fast scanning and structured results for large log files. It ships as a local daemon plus thin clients (CLI bootstrap, VSCode extension, UltraEdit scripts). It is not intended for public distribution.

## Install (internal)

1. Build the engine jar:
   - `./gradlew -p engine shadowJar`
2. Copy `engine/build/libs/wlh-engine-1.0.0.jar` to `WLH_HOME/engine/wlh-engine.jar` or host it internally and let the bootstrap download it.
3. Put `bootstrap/wlh.sh` (or `bootstrap/wlh.bat`) on PATH as `wlh`.

## Usage

Common commands:
- `wlh status`
- `wlh scan --mode fast_then_full /path/to/log.txt`
- `wlh versions /path/to/log.txt`
- `wlh crashes /path/to/log.txt`
- `wlh tags /path/to/log.txt --tag MyTag --limit 50 --offset 0`
- `wlh json-blocks /path/to/log.txt --limit 20`
- `wlh decrypt /path/to/log.txt --jar /path/to/dec.jar --timeout 60`
- `wlh adb devices`
- `wlh adb run --serial SERIAL -- shell getprop`

## Base URL configuration

WLH resolves the update base URL in this order:
1. CLI `--base-url`
2. `WLH_BASE_URL` environment variable
3. `WLH_HOME/config/wlh.json` with `updateBaseUrl`
4. Default placeholder `__WLH_BASE_URL__`

## Troubleshooting

- Java not found: set `JAVA_HOME` or ensure `java` is on PATH.
- Stale daemon: `wlh stop` then `wlh start`.
- Update failures: WLH continues using the last installed engine when possible.

See `docs/usage.md` and `docs/troubleshooting.md` for more detail.
