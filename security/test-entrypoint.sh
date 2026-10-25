#!/bin/sh
set -eu
directory=$(mktemp -d)
trap 'rm -rf "$directory"' EXIT HUP INT TERM
cat > "$directory/java" <<'MOCK'
#!/bin/sh
if [ "$1" = "-cp" ]; then
    exit "${CHECK_EXIT:-0}"
fi
printf '%s\n' "$@"
MOCK
chmod +x "$directory/java"

actual=$(PATH="$directory:$PATH" sh security/docker-entrypoint.sh \
    --spring.profiles.active=production 'argument with spaces')
expected=$(printf '%s\n' -jar /app/app.jar --spring.profiles.active=production 'argument with spaces')
test "$actual" = "$expected"

# An unsafe store must stop startup before the application command executes.
if PATH="$directory:$PATH" CHECK_EXIT=42 sh security/docker-entrypoint.sh > "$directory/output"; then
    echo 'FAIL: application started after failed trust check' >&2
    exit 1
else
    test "$?" -eq 42
    test ! -s "$directory/output"
fi
echo 'PASS: application arguments preserved; failed trust check stops startup'
