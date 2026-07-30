#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/admin-web"
npm install --legacy-peer-deps
npm run build
echo "Admin UI built into src/main/resources/static/admin"
