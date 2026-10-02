#!/bin/bash
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
export CATALINA_HOME="$RAIZ/herramientas/apache-tomcat-10.1.60"
export CATALINA_PID="$RAIZ/.runtime/tomcat.pid"
mkdir -p "$RAIZ/.runtime"
if [[ -f "$CATALINA_PID" ]] && kill -0 "$(cat "$CATALINA_PID")" 2>/dev/null; then
    echo "Tomcat ya está iniciado. Deténlo antes de volver a desplegar."
    exit 1
fi
if lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
    echo "El puerto 8080 está ocupado. No se modificó el servidor que lo utiliza."
    exit 1
fi
bash "$RAIZ/scripts/compilar.sh"
cp "$RAIZ/target/actividad2.war" "$CATALINA_HOME/webapps/actividad2.war"
"$CATALINA_HOME/bin/catalina.sh" start
echo "Aplicación: http://localhost:8080/actividad2/"
echo "Para registro y foros necesitas MySQL/MariaDB y haber importado database/db_actividad2.sql."
