#!/bin/sh
# Launches a Python hook with Python 3.10+ (python3, python, or the Windows 'py' launcher): the version the hook tests
# run on. Claude Code runs hook commands with sh on macOS/Linux and with Git Bash on Windows. Windows needs Git for
# Windows: without Git Bash the hook command can't run, and per the hooks docs a failed hook can block tool calls.
# If no suitable Python is found the hook exits 1: a non-blocking error Claude Code reports, which the path guard's
# onFailure "block" turns into a blocked call. A missing interpreter shows up instead of silently switching the gates off.
script="$1"
shift
dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ok='import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)'
for py in python3 python; do
  if command -v "$py" >/dev/null 2>&1 && "$py" -c "$ok" >/dev/null 2>&1; then
    exec "$py" "$dir/$script" "$@"
  fi
done
if command -v py >/dev/null 2>&1 && py -3 -c "$ok" >/dev/null 2>&1; then
  exec py -3 "$dir/$script" "$@"
fi
echo "kimetsu hooks: Python 3.10+ not found, so $script did not run and its checks are OFF. Install Python 3.10+." >&2
exit 1
