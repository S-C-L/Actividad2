#!/bin/bash
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
export CATALINA_HOME="$RAIZ/herramientas/apache-tomcat-10.1.60"
export CATALINA_PID="$RAIZ/.runtime/tomcat.pid"
if [[ ! -f "$CATALINA_PID" ]]; then
    echo "No hay un Tomcat iniciado por este proyecto."
    exit 0
fi
"$CATALINA_HOME/bin/catalina.sh" stop
