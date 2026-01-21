# WLH Usage

WLH starts a local daemon per `WLH_HOME` and proxies all commands through it. The daemon uses a streaming scan and caches results by file size and mtime.

## Examples

- Scan:
  - `wlh scan /path/to/log.txt`
  - `wlh scan --force /path/to/log.txt`
- Fetch results:
  - `wlh versions /path/to/log.txt`
  - `wlh crashes /path/to/log.txt`

## UltraEdit 18 scripts

Place `wlh.config.json` next to the log file (see `ue18-scripts/config.template.json`), then:

- `scan.js`: run scan and open `<log>.wlhview.txt` summary.
- `view_results.js`: open summary from existing `.wlhresult`.
- `decrypt.js`: decrypt current file and open output.
- `jump.js`: in the `.wlhview.txt` file, select a line like `L123` and run to jump.
- `open_config.js`: open `wlh.config.json`.

## Scan Rules (current)

- Build properties:
  - Matches lines of form `[ro.build.XXX]: [value]` (and related keys).
  - Extracts key/value pairs and stores them in `buildProps`.
- Versions:
  - `Package [<name>] (<id>):` → scan up to 30 lines for `codePath`, `versionCode`, `versionName`.
  - `mPackageName='<name>'` → scan next 3 lines for `VersionName:` and `VersionCode:`.
  - Output: `versionName (versionCode)`, with `[System]` if `codePath` contains `/system/app`.
- Crashes:
  - `FATAL EXCEPTION:` + next `Process: <package>` line, then up to 5 `AndroidRuntime` lines.
  - `APP CRASHED` + next line containing `CRASH: <package>`, then up to 5 `CRASH` lines.
  - `ANR in <package>` + next 5 lines.

## WLH_HOME

Default locations:
- Windows: `%USERPROFILE%\.wlh`
- Linux/macOS: `~/.wlh`

Override using `--home` only. Environment variables are ignored.
