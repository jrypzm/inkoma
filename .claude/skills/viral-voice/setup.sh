#!/bin/bash
# Adds the free voice engine to the toolkit.
set -e
TK="${VIRAL_TOOLKIT:-$HOME/.viral-agent}"
mkdir -p "$TK"
[ -d "$TK/py" ] || python3 -m venv "$TK/py"
PIP="$TK/py/bin/pip"; [ -x "$PIP" ] || PIP="$TK/py/Scripts/pip"
"$PIP" install --quiet --disable-pip-version-check edge-tts==7.3.1
echo "voice engine ready"
