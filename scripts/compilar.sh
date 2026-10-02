#!/bin/bash
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$RAIZ"
"$RAIZ/herramientas/apache-maven-3.9.16/bin/mvn" --batch-mode clean package
