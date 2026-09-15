#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
echo "TaxData 7.1.2 local production-test starting..."
echo "Local URL: http://localhost:8080"
chmod +x ./gradlew
if command -v lsof >/dev/null 2>&1 && lsof -iTCP:8080 -sTCP:LISTEN -t >/dev/null 2>&1; then
  echo "ERROR: port 8080 is already in use. Stop the old process first."
  exit 2
fi
echo "[1/2] Building server + TaxData Local Agent..."
./gradlew clean bootJar -x test --no-daemon --max-workers=1
test -s build/libs/taxdata-server.jar
test -s build/libs/taxdata-agent.jar
echo "[2/2] Starting TaxData on http://localhost:8080 ..."
java -Dtaxdata.agent.jar="$(pwd)/build/libs/taxdata-agent.jar" -jar build/libs/taxdata-server.jar
