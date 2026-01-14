# WLH Usage

WLH starts a local daemon per `WLH_HOME` and proxies all commands through it. The daemon uses a streaming scan and caches results by file size and mtime.

## Examples

- Fast scan:
  - `wlh scan --mode fast /path/to/log.txt`
- Fast then full (recommended):
  - `wlh scan --mode fast_then_full /path/to/log.txt`
- Fetch results:
  - `wlh versions /path/to/log.txt`
  - `wlh crashes /path/to/log.txt`

## WLH_HOME

Default locations:
- Windows: `%USERPROFILE%\.wlh`
- Linux/macOS: `~/.wlh`

Override using `--home` only. Environment variables are ignored.
