#!/bin/sh
set -eu
java -cp /opt/java-security TrustStorePolicy
exec java -jar /app/app.jar "$@"
