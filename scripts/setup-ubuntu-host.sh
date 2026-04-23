#!/usr/bin/env bash
set -Eeuo pipefail

trap 'echo "Error on line $LINENO"; exit 1' ERR

if [[ "${EUID}" -eq 0 ]]; then
  echo "Run this script as your normal user, not root."
  echo "It will use sudo when needed."
  exit 1
fi

if ! command -v dpkg >/dev/null 2>&1; then
  echo "This script targets Ubuntu/Debian systems with dpkg."
  exit 1
fi

ARCH="$(dpkg --print-architecture)"
if [[ "$ARCH" != "amd64" && "$ARCH" != "arm64" ]]; then
  echo "Unsupported architecture: $ARCH"
  echo "This script currently supports amd64 and arm64."
  exit 1
fi

GO_ARCH="$ARCH"
DOCKER_ARCH="$ARCH"

echo "==> Updating apt and installing base packages"
sudo apt-get update
sudo apt-get install -y \
  ca-certificates \
  curl \
  wget \
  gnupg \
  lsb-release \
  software-properties-common \
  apt-transport-https \
  build-essential \
  git \
  unzip \
  tar \
  jq \
  dnsutils \
  python3 \
  python3-pip \
  ripgrep

echo "==> Installing Java (JDK 21 preferred, fallback to JDK 17) and Maven"
if sudo apt-get install -y openjdk-21-jdk maven; then
  JAVA_BIN_DIR="/usr/lib/jvm/java-21-openjdk-${ARCH}/bin"
else
  echo "openjdk-21-jdk not available; falling back to openjdk-17-jdk"
  sudo apt-get install -y openjdk-17-jdk maven
  JAVA_BIN_DIR="/usr/lib/jvm/java-17-openjdk-${ARCH}/bin"
fi

if [[ -x "${JAVA_BIN_DIR}/java" ]]; then
  sudo update-alternatives --set java "${JAVA_BIN_DIR}/java" || true
fi
if [[ -x "${JAVA_BIN_DIR}/javac" ]]; then
  sudo update-alternatives --set javac "${JAVA_BIN_DIR}/javac" || true
fi

echo "==> Removing conflicting Docker packages if present"
for pkg in docker.io docker-doc docker-compose docker-compose-v2 podman-docker containerd runc; do
  sudo apt-get remove -y "$pkg" >/dev/null 2>&1 || true
done

echo "==> Setting up Docker apt repository"
sudo install -m 0755 -d /etc/apt/keyrings
if [[ ! -f /etc/apt/keyrings/docker.asc ]]; then
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo tee /etc/apt/keyrings/docker.asc >/dev/null
fi
sudo chmod a+r /etc/apt/keyrings/docker.asc

UBUNTU_CODENAME="$(
  . /etc/os-release
  echo "${VERSION_CODENAME}"
)"

echo \
  "deb [arch=${DOCKER_ARCH} signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${UBUNTU_CODENAME} stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null

echo "==> Installing Docker Engine and Compose plugin"
sudo apt-get update
sudo apt-get install -y \
  docker-ce \
  docker-ce-cli \
  containerd.io \
  docker-buildx-plugin \
  docker-compose-plugin

echo "==> Enabling Docker"
sudo systemctl enable docker
sudo systemctl start docker

echo "==> Adding ${USER} to docker group"
sudo usermod -aG docker "$USER" || true

echo "==> Installing latest stable Go"
GO_VERSION="$(curl -fsSL https://go.dev/VERSION?m=text | head -n1)"
GO_TARBALL="${GO_VERSION}.linux-${GO_ARCH}.tar.gz"

curl -fsSL "https://go.dev/dl/${GO_TARBALL}" -o "/tmp/${GO_TARBALL}"
sudo rm -rf /usr/local/go
sudo tar -C /usr/local -xzf "/tmp/${GO_TARBALL}"
rm -f "/tmp/${GO_TARBALL}"

PROFILE_FILE="$HOME/.bashrc"

if ! grep -q '/usr/local/go/bin' "$PROFILE_FILE"; then
  echo 'export PATH=/usr/local/go/bin:$PATH' >> "$PROFILE_FILE"
fi

if ! grep -q '$(go env GOPATH)/bin' "$PROFILE_FILE"; then
  echo 'export PATH="$PATH:$(go env GOPATH)/bin"' >> "$PROFILE_FILE"
fi

if ! grep -q 'export JAVA_HOME=' "$PROFILE_FILE"; then
  echo 'export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"' >> "$PROFILE_FILE"
fi

if ! grep -q 'export PATH="$JAVA_HOME/bin:$PATH"' "$PROFILE_FILE"; then
  echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> "$PROFILE_FILE"
fi

export PATH=/usr/local/go/bin:$PATH

echo "==> Installing grpcurl"
go install github.com/fullstorydev/grpcurl/cmd/grpcurl@latest

GOBIN_PATH="$(go env GOPATH)/bin"
export PATH="$PATH:${GOBIN_PATH}"

echo "==> Versions"
echo "--- docker ---"
docker --version || true
echo "--- docker compose ---"
docker compose version || true
echo "--- go ---"
go version || true
echo "--- java ---"
java -version || true
echo "--- javac ---"
javac -version || true
echo "--- maven ---"
mvn -version || true
echo "--- grpcurl ---"
"${GOBIN_PATH}/grpcurl" -help >/dev/null 2>&1 && echo "grpcurl installed at ${GOBIN_PATH}/grpcurl" || true
echo "--- ripgrep ---"
rg --version || true

cat <<EOF

Bootstrap complete.

Important:
1. Start a new shell or run: newgrp docker
2. Then verify:
   docker ps
   docker compose version
   java -version
   javac -version
   mvn -version
   go version
   grpcurl -help
   rg --version

If grpcurl is not found immediately, run:
   export PATH="\$PATH:$(go env GOPATH)/bin"
EOF
