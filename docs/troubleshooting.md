# Troubleshooting

- `status` reports not_running: start the daemon with `wlh start`.
- Download fails: ensure the internal base URL is reachable and configured.
- Slow scans: install `rg` (ripgrep) so WLH can use it automatically for faster package-window scans.
- Updated engine JAR but behavior is unchanged: stop/start the daemon to reload it.
- Large file jump errors in VSCode: increase `files.maxMemoryForLargeFilesMB` and `files.maxFileSize`.
