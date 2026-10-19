#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Linux" ]] || [[ ! -r /etc/os-release ]]; then
  echo "This script supports Ubuntu Linux only." >&2
  exit 1
fi

# shellcheck disable=SC1091
source /etc/os-release
if [[ "${ID:-}" != "ubuntu" ]]; then
  echo "Expected Ubuntu, found ${PRETTY_NAME:-unknown}." >&2
  exit 1
fi

deploy_user="${SUDO_USER:-$(id -un)}"

sudo apt-get update
sudo apt-get install -y ca-certificates curl git nginx certbot python3-certbot-nginx

if ! command -v docker >/dev/null 2>&1; then
  sudo install -m 0755 -d /etc/apt/keyrings
  sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
    -o /etc/apt/keyrings/docker.asc
  sudo chmod a+r /etc/apt/keyrings/docker.asc

  ubuntu_codename="${UBUNTU_CODENAME:-${VERSION_CODENAME}}"
  architecture="$(dpkg --print-architecture)"
  docker_source="Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${ubuntu_codename}
Components: stable
Architectures: ${architecture}
Signed-By: /etc/apt/keyrings/docker.asc"
  printf '%s\n' "${docker_source}" | sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null

  sudo apt-get update
  sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi

sudo systemctl enable --now docker
sudo systemctl enable --now nginx
sudo usermod -aG docker "${deploy_user}"

sudo docker version --format 'Docker Engine {{.Server.Version}} ({{.Server.Os}}/{{.Server.Arch}})'
sudo docker compose version
nginx -v
certbot --version

echo
echo "Bootstrap complete. Log out and reconnect once so Docker group membership applies."
echo "Do not open TCP 8088 in the OCI security list or the host firewall."
