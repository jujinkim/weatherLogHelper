#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '%s\n' "$*" >&2
}

json_error() {
  local msg="$1"
  printf '{"status":"error","message":"%s"}\n' "$msg"
}

json_ok() {
  printf '{"status":"ok"}%s\n' "${1:-}"
}

resolve_default_home() {
  local os
  os=$(uname -s 2>/dev/null || echo unknown)
  case "$os" in
    MINGW*|MSYS*|CYGWIN*)
      printf '%s' "${USERPROFILE:-$HOME}/.wlh"
      ;;
    *)
      printf '%s' "${HOME}/.wlh"
      ;;
  esac
}

sha256_file() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
  else
    return 1
  fi
}

json_get_field() {
  local field="$1"
  python3 - "$field" <<'PY'
import json
import sys
data = sys.stdin.read()
try:
    obj = json.loads(data) if data else {}
except Exception:
    obj = {}
print(obj.get(sys.argv[1], ""))
PY
}

extract_job_id() {
  local json="$1"
  local job_id=""
  job_id=$(printf '%s' "$json" | json_get_field jobId)
  if [ -z "$job_id" ]; then
    job_id=$(printf '%s' "$json" | sed -n 's/.*"jobId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)
  fi
  printf '%s' "$job_id"
}

resolve_java() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    printf '%s' "${JAVA_HOME}/bin/java"
  elif command -v java >/dev/null 2>&1; then
    command -v java
  else
    return 1
  fi
}

acquire_lock() {
  local lock_dir="$1"
  local waited=0
  while ! mkdir "$lock_dir" 2>/dev/null; do
    waited=$((waited + 1))
    if [ "$waited" -gt 50 ]; then
      return 1
    fi
    sleep 0.1
  done
}

release_lock() {
  local lock_dir="$1"
  rmdir "$lock_dir" 2>/dev/null || true
}

read_config_base_url() {
  local config_file="$1"
  python3 - <<PY
import json
from pathlib import Path
p = Path("$config_file")
if not p.exists():
    raise SystemExit(1)
obj = json.loads(p.read_text())
print(obj.get("updateBaseUrl", ""))
PY
}

http_get() {
  local url="$1"
  curl -sS "$url"
}

http_post() {
  local url="$1"
  local payload="$2"
  curl -sS -X POST -H 'Content-Type: application/json' -d "$payload" "$url"
}

parse_daemon_json() {
  local file="$1"
  python3 - <<PY
import json
from pathlib import Path
p = Path("$file")
if not p.exists():
    raise SystemExit(1)
obj = json.loads(p.read_text())
print(obj.get("port", ""))
print(obj.get("pid", ""))
PY
}

wait_for_daemon() {
  local daemon_json="$1"
  local port=""
  local pid=""
  local attempts=0
  while [ "$attempts" -lt 50 ]; do
    if [ -f "$daemon_json" ]; then
      readarray -t vals < <(parse_daemon_json "$daemon_json" || true)
      port="${vals[0]:-}"
      pid="${vals[1]:-}"
      if [ -n "$port" ] && [ -n "$pid" ]; then
        if http_get "http://127.0.0.1:${port}/api/v1/status" >/dev/null 2>&1; then
          printf '%s\n' "$port"
          return 0
        fi
      fi
    fi
    attempts=$((attempts + 1))
    sleep 0.2
  done
  return 1
}

ensure_dirs() {
  mkdir -p "$WLH_HOME/engine" "$WLH_HOME/daemon" "$WLH_HOME/cache" "$WLH_HOME/config" "$WLH_HOME/logs"
}

write_starting_file() {
  local path="$WLH_HOME/daemon/daemon.starting.json"
  cat <<JSON > "$path"
{"pid": $$, "startedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"}
JSON
}

remove_starting_file() {
  rm -f "$WLH_HOME/daemon/daemon.starting.json"
}

ensure_engine() {
  if [ "$NO_UPDATE" -eq 1 ] && [ -f "$WLH_HOME/engine/wlh-engine.jar" ]; then
    return 0
  fi
  if [ -z "$BASE_URL" ]; then
    if [ -f "$WLH_HOME/engine/wlh-engine.jar" ]; then
      log "base URL missing, skipping update"
      return 0
    fi
    json_error "base_url_missing"
    exit 1
  fi

  local lock_dir="$WLH_HOME/engine/download.lock"
  if ! acquire_lock "$lock_dir"; then
    log "download lock busy"
  fi
  trap 'release_lock "$lock_dir"' EXIT

  local latest_url="${BASE_URL}/latest.json"
  local latest_json
  if ! latest_json=$(http_get "$latest_url" 2>/dev/null); then
    if [ -f "$WLH_HOME/engine/wlh-engine.jar" ]; then
      log "update failed, using existing engine"
      release_lock "$lock_dir"
      trap - EXIT
      return 0
    fi
    release_lock "$lock_dir"
    trap - EXIT
    json_error "update_failed"
    exit 1
  fi

  local version artifact sha
  readarray -t latest_vals < <(printf '%s' "$latest_json" | python3 - <<'PY'
import json
import sys
data = sys.stdin.read()
obj = json.loads(data) if data else {}
print(obj.get('version',''))
print(obj.get('artifact',''))
print(obj.get('sha256',''))
PY
)
  version="${latest_vals[0]:-}"
  artifact="${latest_vals[1]:-}"
  sha="${latest_vals[2]:-}"

  if [ -z "$version" ] || [ -z "$artifact" ] || [ -z "$sha" ]; then
    log "invalid latest.json"
    release_lock "$lock_dir"
    trap - EXIT
    json_error "invalid_latest"
    exit 1
  fi

  local tmpfile
  tmpfile=$(mktemp)
  if ! curl -sS -L -o "$tmpfile" "${BASE_URL}/${artifact}"; then
    rm -f "$tmpfile"
    if [ -f "$WLH_HOME/engine/wlh-engine.jar" ]; then
      log "download failed, using existing engine"
      release_lock "$lock_dir"
      trap - EXIT
      return 0
    fi
    release_lock "$lock_dir"
    trap - EXIT
    json_error "download_failed"
    exit 1
  fi

  local actual_sha
  actual_sha=$(sha256_file "$tmpfile" || echo "")
  if [ "$actual_sha" != "$sha" ]; then
    rm -f "$tmpfile"
    if [ -f "$WLH_HOME/engine/wlh-engine.jar" ]; then
      log "sha mismatch, using existing engine"
      release_lock "$lock_dir"
      trap - EXIT
      return 0
    fi
    release_lock "$lock_dir"
    trap - EXIT
    json_error "sha_mismatch"
    exit 1
  fi

  mv "$tmpfile" "$WLH_HOME/engine/wlh-engine.jar"
  cat <<JSON > "$WLH_HOME/engine/installed.json"
{"version":"$version","installedAt":"$(date -u +%Y-%m-%dT%H:%M:%SZ)"}
JSON

  release_lock "$lock_dir"
  trap - EXIT
}

is_daemon_alive() {
  local daemon_json="$WLH_HOME/daemon/daemon.json"
  if [ ! -f "$daemon_json" ]; then
    return 1
  fi
  readarray -t vals < <(parse_daemon_json "$daemon_json" || true)
  local port="${vals[0]:-}"
  local pid="${vals[1]:-}"
  if [ -z "$port" ] || [ -z "$pid" ]; then
    return 1
  fi
  if ! kill -0 "$pid" 2>/dev/null; then
    rm -f "$daemon_json"
    return 1
  fi
  if ! http_get "http://127.0.0.1:${port}/api/v1/status" >/dev/null 2>&1; then
    rm -f "$daemon_json"
    return 1
  fi
  return 0
}

start_daemon() {
  if is_daemon_alive; then
    json_ok ',"started":false'
    return 0
  fi

  local lock_dir="$WLH_HOME/daemon/daemon.lock"
  if ! acquire_lock "$lock_dir"; then
    if wait_for_daemon "$WLH_HOME/daemon/daemon.json" >/dev/null; then
      json_ok ',"started":false'
      return 0
    fi
    json_error "daemon_lock_busy"
    exit 1
  fi
  trap 'remove_starting_file; release_lock "$lock_dir"' EXIT

  if is_daemon_alive; then
    release_lock "$lock_dir"
    remove_starting_file
    trap - EXIT
    json_ok ',"started":false'
    return 0
  fi

  local java_cmd
  if ! java_cmd=$(resolve_java); then
    release_lock "$lock_dir"
    remove_starting_file
    trap - EXIT
    json_error "java_not_found"
    exit 1
  fi

  ensure_engine
  write_starting_file

  nohup "$java_cmd" -jar "$WLH_HOME/engine/wlh-engine.jar" --home "$WLH_HOME" --port 0 \
    >> "$WLH_HOME/logs/daemon.log" 2>> "$WLH_HOME/logs/daemon.log" &

  if ! wait_for_daemon "$WLH_HOME/daemon/daemon.json" >/dev/null; then
    release_lock "$lock_dir"
    remove_starting_file
    trap - EXIT
    json_error "daemon_start_failed"
    exit 1
  fi

  release_lock "$lock_dir"
  remove_starting_file
  trap - EXIT
  json_ok ',"started":true'
}

stop_daemon() {
  if ! is_daemon_alive; then
    json_ok ',"stopped":false'
    return 0
  fi
  readarray -t vals < <(parse_daemon_json "$WLH_HOME/daemon/daemon.json" || true)
  local port="${vals[0]:-}"
  if [ -z "$port" ]; then
    json_error "daemon_port_missing"
    exit 1
  fi
  http_post "http://127.0.0.1:${port}/api/v1/shutdown" '{}' >/dev/null 2>&1 || true
  json_ok ',"stopped":true'
}

proxy_status() {
  if ! is_daemon_alive; then
    printf '{"status":"not_running"}\n'
    return 0
  fi
  readarray -t vals < <(parse_daemon_json "$WLH_HOME/daemon/daemon.json" || true)
  local port="${vals[0]:-}"
  if [ -z "$port" ]; then
    json_error "daemon_port_missing"
    exit 1
  fi
  http_get "http://127.0.0.1:${port}/api/v1/status" || json_error "status_failed"
}

proxy_scan() {
  local file="$1"
  if ! is_daemon_alive; then
    start_daemon >/dev/null
  fi
  readarray -t vals < <(parse_daemon_json "$WLH_HOME/daemon/daemon.json" || true)
  local port="${vals[0]:-}"
  local payload
  payload=$(printf '{"file":"%s"}' "$file")
  http_post "http://127.0.0.1:${port}/api/v1/scan" "$payload" || json_error "scan_failed"
}

wait_job() {
  local port="$1"
  local job_id="$2"
  local attempts=0
  while [ "$attempts" -lt 240 ]; do
    local status_json
    status_json=$(http_get "http://127.0.0.1:${port}/api/v1/job/${job_id}" || true)
    if [ -n "$status_json" ]; then
      local status
      status=$(printf '%s' "$status_json" | json_get_field status)
      if [ "$status" = "completed" ]; then
        return 0
      fi
      if [ "$status" = "failed" ]; then
        return 1
      fi
    fi
    attempts=$((attempts + 1))
    sleep 0.5
  done
  return 1
}

proxy_results() {
  local endpoint="$1"
  local query="$2"
  if ! is_daemon_alive; then
    start_daemon >/dev/null
  fi
  readarray -t vals < <(parse_daemon_json "$WLH_HOME/daemon/daemon.json" || true)
  local port="${vals[0]:-}"
  http_get "http://127.0.0.1:${port}/api/v1/result/${endpoint}${query}" || json_error "result_failed"
}

proxy_decrypt() {
  local file="$1"
  local jar="$2"
  local timeout="$3"
  if ! is_daemon_alive; then
    start_daemon >/dev/null
  fi
  readarray -t vals < <(parse_daemon_json "$WLH_HOME/daemon/daemon.json" || true)
  local port="${vals[0]:-}"
  local payload
  payload=$(printf '{"file":"%s","jar":"%s","timeoutSeconds":%s}' "$file" "$jar" "$timeout")
  http_post "http://127.0.0.1:${port}/api/v1/decrypt" "$payload" || json_error "decrypt_failed"
}

proxy_adb_devices() {
  local adb_path="$1"
  if ! is_daemon_alive; then
    start_daemon >/dev/null
  fi
  readarray -t vals < <(parse_daemon_json "$WLH_HOME/daemon/daemon.json" || true)
  local port="${vals[0]:-}"
  local query=""
  if [ -n "$adb_path" ]; then
    query="?adb=$(python3 - <<PY
import urllib.parse
print(urllib.parse.quote('$adb_path'))
PY
)"
  fi
  http_get "http://127.0.0.1:${port}/api/v1/adb/devices${query}" || json_error "adb_failed"
}

proxy_adb_run() {
  local serial="$1"
  local adb_path="$2"
  shift 2
  local args=("$@")
  if ! is_daemon_alive; then
    start_daemon >/dev/null
  fi
  readarray -t vals < <(parse_daemon_json "$WLH_HOME/daemon/daemon.json" || true)
  local port="${vals[0]:-}"
  local args_json
  args_json=$(python3 - "$@" <<'PY'
import json
import sys
print(json.dumps(sys.argv[1:]))
PY
)
  local payload
  if [ -n "$adb_path" ]; then
    payload=$(printf '{"serial":"%s","adb":"%s","args":%s}' "$serial" "$adb_path" "$args_json")
  else
    payload=$(printf '{"serial":"%s","args":%s}' "$serial" "$args_json")
  fi
  http_post "http://127.0.0.1:${port}/api/v1/adb/run" "$payload" || json_error "adb_failed"
}

WLH_HOME=""
BASE_URL=""
NO_UPDATE=0

ARGS=("$@")
index=0
while [ $index -lt ${#ARGS[@]} ]; do
  case "${ARGS[$index]}" in
    --home)
      WLH_HOME="${ARGS[$((index + 1))]:-}"
      index=$((index + 2))
      ;;
    --base-url)
      BASE_URL="${ARGS[$((index + 1))]:-}"
      index=$((index + 2))
      ;;
    --no-update)
      NO_UPDATE=1
      index=$((index + 1))
      ;;
    *)
      break
      ;;
  esac
done

COMMAND="${ARGS[$index]:-}"
index=$((index + 1))
REMAINING=("${ARGS[@]:$index}")

if [ -z "$WLH_HOME" ]; then
  WLH_HOME=$(resolve_default_home)
fi

ensure_dirs

if [ -z "$BASE_URL" ]; then
  config_url=$(read_config_base_url "$WLH_HOME/config/wlh.json" 2>/dev/null || true)
  if [ -n "$config_url" ]; then
    BASE_URL="$config_url"
  fi
fi

case "$COMMAND" in
  status)
    if [ "${REMAINING[0]:-}" = "--no-update" ]; then
      NO_UPDATE=1
    fi
    if [ "$NO_UPDATE" -eq 0 ]; then
      ensure_engine || exit 1
    fi
    proxy_status
    ;;
  start)
    start_daemon
    ;;
  stop)
    stop_daemon
    ;;
  restart)
    stop_daemon >/dev/null
    start_daemon
    ;;
  update)
    ensure_engine
    json_ok
    ;;
  scan)
    file=""
    i=0
    while [ $i -lt ${#REMAINING[@]} ]; do
      case "${REMAINING[$i]}" in
        *)
          file="${REMAINING[$i]}"
          i=$((i + 1))
          ;;
      esac
    done
    if [ -z "$file" ]; then
      json_error "missing_file"
      exit 1
    fi
    proxy_scan "$file"
    ;;
  versions)
    file="${REMAINING[0]:-}"
    if [ -z "$file" ]; then
      json_error "missing_file"
      exit 1
    fi
    scan_json=$(proxy_scan "$file")
    job_id=$(extract_job_id "$scan_json")
    if [ -z "$job_id" ]; then
      printf '%s\n' "$scan_json"
      exit 1
    fi
    readarray -t vals < <(parse_daemon_json "$WLH_HOME/daemon/daemon.json" || true)
    port="${vals[0]:-}"
    if wait_job "$port" "$job_id"; then
      proxy_results "versions" ""
    else
      json_error "scan_failed"
    fi
    ;;
  crashes)
    file="${REMAINING[0]:-}"
    if [ -z "$file" ]; then
      json_error "missing_file"
      exit 1
    fi
    scan_json=$(proxy_scan "$file")
    job_id=$(extract_job_id "$scan_json")
    if [ -z "$job_id" ]; then
      printf '%s\n' "$scan_json"
      exit 1
    fi
    readarray -t vals < <(parse_daemon_json "$WLH_HOME/daemon/daemon.json" || true)
    port="${vals[0]:-}"
    if wait_job "$port" "$job_id"; then
      proxy_results "crashes" ""
    else
      json_error "scan_failed"
    fi
    ;;
  decrypt)
    file=""
    jar=""
    timeout="30"
    i=0
    while [ $i -lt ${#REMAINING[@]} ]; do
      case "${REMAINING[$i]}" in
        --jar)
          jar="${REMAINING[$((i + 1))]:-}"
          i=$((i + 2))
          ;;
        --timeout)
          timeout="${REMAINING[$((i + 1))]:-30}"
          i=$((i + 2))
          ;;
        *)
          file="${REMAINING[$i]}"
          i=$((i + 1))
          ;;
      esac
    done
    if [ -z "$file" ] || [ -z "$jar" ]; then
      json_error "missing_fields"
      exit 1
    fi
    proxy_decrypt "$file" "$jar" "$timeout"
    ;;
  adb)
    sub="${REMAINING[0]:-}"
    case "$sub" in
      devices)
        adb_path=""
        if [ "${REMAINING[1]:-}" = "--adb" ]; then
          adb_path="${REMAINING[2]:-}"
        fi
        proxy_adb_devices "$adb_path"
        ;;
      run)
        serial=""
        adb_path=""
        args_start=0
        i=1
        while [ $i -lt ${#REMAINING[@]} ]; do
          case "${REMAINING[$i]}" in
            --serial)
              serial="${REMAINING[$((i + 1))]:-}"
              i=$((i + 2))
              ;;
            --adb)
              adb_path="${REMAINING[$((i + 1))]:-}"
              i=$((i + 2))
              ;;
            --)
              args_start=$((i + 1))
              break
              ;;
            *)
              i=$((i + 1))
              ;;
          esac
        done
        if [ -z "$serial" ]; then
          json_error "missing_serial"
          exit 1
        fi
        args=("${REMAINING[@]:$args_start}")
        if [ ${#args[@]} -eq 0 ]; then
          json_error "missing_args"
          exit 1
        fi
        proxy_adb_run "$serial" "$adb_path" "${args[@]}"
        ;;
      *)
        json_error "unknown_adb_command"
        ;;
    esac
    ;;
  *)
    json_error "unknown_command"
    ;;
esac
