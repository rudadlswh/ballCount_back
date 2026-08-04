#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <image-name> <image-tag>" >&2
  exit 2
fi

image_name="$1"
image_tag="$2"
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
health_url="http://127.0.0.1:8088/healthz"

cd "${project_dir}"

if [[ ! -f .env ]]; then
  echo "ERROR: ${project_dir}/.env is missing" >&2
  exit 1
fi

compose() {
  IMAGE_NAME="${image_name}" IMAGE_TAG="${image_tag}" docker compose "$@"
}

compose config --quiet
compose pull backend
compose up -d --remove-orphans backend

for _ in $(seq 1 60); do
  if curl --fail --silent --show-error "${health_url}" >/dev/null 2>&1; then
    compose ps backend
    echo "Deployment succeeded: ${image_name}:${image_tag}"
    exit 0
  fi
  sleep 5
done

compose ps backend >&2
compose logs --tail=300 backend >&2
echo "ERROR: health check failed for ${image_name}:${image_tag}" >&2
exit 1
