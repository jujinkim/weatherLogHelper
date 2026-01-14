# WLH Usage

WLH starts a local daemon per `WLH_HOME` and proxies all commands through it. The daemon uses a streaming scan and caches results by file size and mtime.

## Examples

- Scan:
  - `wlh scan /path/to/log.txt`
- Fetch results:
  - `wlh versions /path/to/log.txt`
  - `wlh crashes /path/to/log.txt`

## Scan Rules (current)

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
