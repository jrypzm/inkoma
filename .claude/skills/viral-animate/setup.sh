#!/bin/bash
# One-time toolkit for the viral skills: ~/.viral-agent holds the browser driver, Three.js and the caption fonts.
set -e
TK="${VIRAL_TOOLKIT:-$HOME/.viral-agent}"
HERE="$(cd "$(dirname "$0")" && pwd)"
command -v node >/dev/null || { echo "Node.js 18 or newer is missing. Install it from https://nodejs.org and run this again."; exit 1; }
command -v ffmpeg >/dev/null || { echo "ffmpeg is missing. On a Mac: brew install ffmpeg. On Windows: winget install ffmpeg."; exit 1; }
mkdir -p "$TK"; cd "$TK"
[ -f package.json ] || echo '{ "name": "viral-agent-toolkit", "version": "1.0.0", "private": true }' > package.json
npm install --silent --no-audit --no-fund playwright-core@1.64.1 three@0.170.0 \
  @fontsource/noto-sans-malayalam@5.3.0 @fontsource/jetbrains-mono@5.3.0 @fontsource/inter@5.3.0
# Google Chrome is reused when it's installed. Otherwise this downloads Chromium once (about 200 MB).
if ! node "$HERE/render.mjs" --doctor; then
  npx playwright-core install chromium-headless-shell
  node "$HERE/render.mjs" --doctor
fi
echo "toolkit ready: $TK"
