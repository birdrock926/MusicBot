#!/usr/bin/env bash
# Automated setup for running multiple JMusicBot instances on Ubuntu Server.
# Usage examples:
#   # 既存の設定ファイルを指定する場合
#   sudo ./scripts/setup-ubuntu.sh botA:config-botA.txt botB:config-botB.txt
#   # 設定ファイルがまだ無い場合はインスタンスIDだけ渡すと対話的にトークン等を聞いて生成
#   sudo ./scripts/setup-ubuntu.sh botA botB
# Notes:
#   - Requires sudo/root (installs packages, writes /opt and /etc/systemd).
#   - Redis is recommended for multi-instance locking. Set JMUSICBOT_REDIS_URI
#     before running to point to your Redis (default: redis://localhost:6379).
#   - If you don't want Redis, set USE_REDIS=false.
set -euo pipefail

if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
  echo "Please run as root (sudo)." >&2
  exit 1
fi

if [[ $# -lt 1 ]]; then
  echo "Usage: sudo $0 botA:configA.txt [botB:configB.txt ...]" >&2
  exit 1
fi

USE_REDIS=${USE_REDIS:-true}
REDIS_URI=${JMUSICBOT_REDIS_URI:-redis://localhost:6379}
JAR_SOURCE=${JAR_SOURCE:-target/JMusicBot.jar}
INSTALL_DIR=/opt/jmusicbot
SERVICE_FILE=/etc/systemd/system/jmusicbot@.service
USER_NAME=jmusicbot

create_config() {
  local instance_id="$1"
  local dest="$2"
  echo "Config not found for ${instance_id}. Launching built-in interactive setup..."
  sudo -u "${USER_NAME}" /usr/bin/java -Dinstance.id="${instance_id}" -Dconfig.file="${dest}" -jar "${INSTALL_DIR}/JMusicBot.jar" configure
  chmod 600 "${dest}" || true
}

echo "[1/6] Installing packages (OpenJDK 17, Redis optional)..."
apt-get update -y
apt-get install -y openjdk-17-jre
if [[ "${USE_REDIS}" == "true" ]]; then
  apt-get install -y redis-server
fi

echo "[2/6] Ensuring service user '${USER_NAME}'..."
if ! id -u "${USER_NAME}" >/dev/null 2>&1; then
  useradd -r -s /usr/sbin/nologin "${USER_NAME}"
fi

echo "[3/6] Preparing install dir ${INSTALL_DIR}..."
mkdir -p "${INSTALL_DIR}"

echo "[4/6] Copying JAR..."
if [[ ! -f "${JAR_SOURCE}" ]]; then
  echo "JAR not found at ${JAR_SOURCE}. Build first (mvn -DskipTests package) or set JAR_SOURCE=path/to/JMusicBot.jar" >&2
  exit 1
fi
cp "${JAR_SOURCE}" "${INSTALL_DIR}/JMusicBot.jar"

echo "[5/6] Copying configs and building instance list..."
INSTANCES=()
for pair in "$@"; do
  if [[ "${pair}" == *:* ]]; then
    id="${pair%%:*}"
    cfg="${pair#*:}"
    if [[ ! -f "${cfg}" ]]; then
      echo "Config not found: ${cfg}" >&2
      exit 1
    fi
    cp "${cfg}" "${INSTALL_DIR}/config-${id}.txt"
  else
    id="${pair}"
    create_config "${id}" "${INSTALL_DIR}/config-${id}.txt"
  fi
  INSTANCES+=("${id}")
done

chown -R "${USER_NAME}:${USER_NAME}" "${INSTALL_DIR}"

echo "[6/6] Writing systemd unit ${SERVICE_FILE}..."
cat >/etc/systemd/system/jmusicbot@.service <<EOF
[Unit]
Description=JMusicBot instance %i
After=network.target redis.service
Wants=network-online.target

[Service]
User=${USER_NAME}
WorkingDirectory=${INSTALL_DIR}
Environment=JMUSICBOT_REDIS_URI=${REDIS_URI}
ExecStart=/usr/bin/java -Dinstance.id=%i -Dconfig.file=${INSTALL_DIR}/config-%i.txt -jar ${INSTALL_DIR}/JMusicBot.jar
Restart=on-failure
RestartSec=5s
EOF

systemctl daemon-reload
for id in "${INSTANCES[@]}"; do
  systemctl enable --now "jmusicbot@${id}"
done

echo "Setup complete. Instances started: ${INSTANCES[*]}"
echo "Check logs with: journalctl -u jmusicbot@<id> -f"

