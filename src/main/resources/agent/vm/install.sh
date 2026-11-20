#!/usr/bin/env bash
# AIMS VM 수집 에이전트 설치 — node_exporter + Grafana Alloy(둘 다 systemd), GPU 가 있으면 dcgm-exporter(Docker) (docs/11 §5.5).
# Alloy 가 로그는 Loki 로, node_exporter·dcgm-exporter 지표는 Thanos Receive 로 보낸다(tb_ns·tb_infra·tb_node 라벨).
#
# 백엔드가 Tumblebug 원격 명령(VM 한 대, ?nodeId=)으로 이 스크립트·alloy.env·config.alloy 를 base64 로 내려 root 로 실행한다.
#   사용법: install.sh <alloy.env> <config.alloy>
# 다시 실행해도 같은 결과다(같은 버전이면 내려받지 않고 설정만 다시 쓰고 재시작). Alloy 읽기 위치는 /var/lib/aims-alloy 에
# 남아 재시작해도 같은 줄을 두 번 보내지 않는다.
#
# 출력 계약(백엔드가 읽는다): 단계마다 "AIMS_STEP=<단계>",
# 성공하면 마지막에 "AIMS_AGENT_OK alloy=<버전> node_exporter=<버전> gpu=<dcgm|skip|none>", 실패하면 "AIMS_ERROR=<사유>" 와 0 이 아닌 종료 코드.
# gpu — dcgm: dcgm-exporter 로 GPU 지표 수집 / skip: GPU 는 있으나 Docker·NVIDIA Container Toolkit 이 없거나 기동 실패
# (사유는 "AIMS_NOTE=" 줄, 설치 자체는 성공) / none: GPU 없음. 드라이버·Docker 는 VM 이미지마다 달라 설치하지 않는다.
set -euo pipefail

ENV_SRC="$1"
CONFIG_SRC="$2"
set -a
# shellcheck disable=SC1090
. "$ENV_SRC"
set +a

step() { echo "AIMS_STEP=$1"; }
fail() { echo "AIMS_ERROR=$1"; exit 1; }
fetch() {
  if command -v curl >/dev/null 2>&1; then curl -fsSL --retry 3 -o "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then wget -q -O "$2" "$1"
  else fail "curl 과 wget 이 모두 없습니다"; fi
}
probe() {
  if command -v curl >/dev/null 2>&1; then curl -fs -o /dev/null "$1"; else wget -q -O /dev/null "$1"; fi
}
# :9400 에서 DCGM GPU 지표가 나오는지 — 본문을 변수로 받아 검사한다(pipefail 에서 grep -q 가 앞 프로세스를 끊어 실패로 보이는 것을 피함)
dcgm_ready() {
  local body
  if command -v curl >/dev/null 2>&1; then body="$(curl -fs http://127.0.0.1:9400/metrics 2>/dev/null)" || return 1
  else body="$(wget -q -O - http://127.0.0.1:9400/metrics 2>/dev/null)" || return 1; fi
  grep -q '^DCGM_FI_DEV_GPU_UTIL' <<<"$body"
}
note() { echo "AIMS_NOTE=$1"; }

step PREPARE
# Tumblebug 이 붙인 hostname 이 /etc/hosts 에 없으면 sudo 가 매번 경고한다(docs/07 §3.1) — 한 번만 정리
grep -q "[[:space:]]$(hostname)\$" /etc/hosts || echo "127.0.1.1 $(hostname)" >> /etc/hosts
case "$(uname -m)" in
  x86_64) ARCH=amd64 ;;
  aarch64 | arm64) ARCH=arm64 ;;
  *) fail "지원하지 않는 아키텍처: $(uname -m)" ;;
esac
command -v systemctl >/dev/null 2>&1 || fail "systemd 가 없습니다"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

step NODE_EXPORTER
if ! /usr/local/bin/node_exporter --version 2>&1 | grep -q "version ${NODE_EXPORTER_VERSION} "; then
  fetch "https://github.com/prometheus/node_exporter/releases/download/v${NODE_EXPORTER_VERSION}/node_exporter-${NODE_EXPORTER_VERSION}.linux-${ARCH}.tar.gz" "$WORK/node_exporter.tar.gz" \
    || fail "node_exporter 내려받기 실패(github.com 아웃바운드 확인)"
  tar xzf "$WORK/node_exporter.tar.gz" -C "$WORK"
  install -m 0755 "$WORK/node_exporter-${NODE_EXPORTER_VERSION}.linux-${ARCH}/node_exporter" /usr/local/bin/node_exporter
fi
id node_exporter >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin node_exporter
cat > /etc/systemd/system/node_exporter.service <<'UNIT'
[Unit]
Description=Prometheus Node Exporter
After=network.target

[Service]
User=node_exporter
Group=node_exporter
Type=simple
ExecStart=/usr/local/bin/node_exporter --collector.cpu --collector.meminfo --collector.diskstats --collector.filesystem --collector.netdev --collector.loadavg --collector.processes --collector.systemd --web.listen-address=:9100
Restart=always

[Install]
WantedBy=multi-user.target
UNIT

step ALLOY
if ! /usr/local/bin/alloy --version 2>&1 | grep -q "version v${ALLOY_VERSION} "; then
  fetch "https://github.com/grafana/alloy/releases/download/v${ALLOY_VERSION}/alloy-linux-${ARCH}.zip" "$WORK/alloy.zip" \
    || fail "Alloy 내려받기 실패(github.com 아웃바운드 확인)"
  if command -v unzip >/dev/null 2>&1; then
    unzip -o -q "$WORK/alloy.zip" -d "$WORK"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -m zipfile -e "$WORK/alloy.zip" "$WORK"
  else
    fail "unzip 과 python3 가 모두 없어 Alloy 압축을 풀 수 없습니다"
  fi
  install -m 0755 "$WORK/alloy-linux-${ARCH}" /usr/local/bin/alloy
fi

step GPU
GPU_STATE=none
if command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi -L >/dev/null 2>&1; then
  if dcgm_ready; then
    # 이미 :9400 에서 GPU 지표를 내는 dcgm-exporter 가 있으면(직접 띄운 것 포함) 그대로 읽는다 — 포트 충돌을 만들지 않는다
    GPU_STATE=dcgm
  elif ! command -v docker >/dev/null 2>&1; then
    GPU_STATE=skip; note "GPU 는 있으나 Docker 가 없어 dcgm-exporter 를 건너뜁니다"
  elif ! command -v nvidia-ctk >/dev/null 2>&1 && ! docker info 2>/dev/null | grep -qi nvidia; then
    GPU_STATE=skip; note "GPU 는 있으나 NVIDIA Container Toolkit 이 없어 dcgm-exporter 를 건너뜁니다"
  else
    docker rm -f aims-dcgm-exporter >/dev/null 2>&1 || true
    # --hostname: dcgm-exporter 는 Hostname 라벨에 자기 호스트 이름을 넣는다 — 안 주면 컨테이너 ID 가 들어간다
    if docker run -d --name aims-dcgm-exporter --restart unless-stopped --hostname "$TB_NODE" \
        --gpus all --cap-add SYS_ADMIN -p 127.0.0.1:9400:9400 "$DCGM_EXPORTER_IMAGE" >/dev/null; then
      for _ in $(seq 1 30); do
        if dcgm_ready; then GPU_STATE=dcgm; break; fi
        sleep 2
      done
      [ "$GPU_STATE" = dcgm ] || { GPU_STATE=skip; note "dcgm-exporter 가 60초 안에 GPU 지표를 내지 않습니다(docker logs aims-dcgm-exporter 확인)"; }
    else
      GPU_STATE=skip; note "dcgm-exporter 컨테이너를 띄우지 못했습니다(이미지 ${DCGM_EXPORTER_IMAGE}, nvcr.io 아웃바운드 확인)"
    fi
  fi
fi

step CONFIG
install -d -m 0755 /etc/aims-alloy /var/lib/aims-alloy
if [ "$GPU_STATE" = dcgm ]; then
  install -m 0644 "$CONFIG_SRC" /etc/aims-alloy/config.alloy
else
  sed '/AIMS:GPU:BEGIN/,/AIMS:GPU:END/d' "$CONFIG_SRC" > /etc/aims-alloy/config.alloy
  chmod 0644 /etc/aims-alloy/config.alloy
fi
install -m 0600 "$ENV_SRC" /etc/aims-alloy/alloy.env
# root 로 돈다 — 포털에서 등록하는 로그 파일은 소유자·권한이 제각각이고(/var/log/secure 는 0600), 들어오는 포트는 없다(UI 는 127.0.0.1)
cat > /etc/systemd/system/aims-alloy.service <<'UNIT'
[Unit]
Description=AIMS collector - logs and metrics (Grafana Alloy)
After=network-online.target
Wants=network-online.target

[Service]
EnvironmentFile=/etc/aims-alloy/alloy.env
ExecStart=/usr/local/bin/alloy run --storage.path=/var/lib/aims-alloy --server.http.listen-addr=127.0.0.1:12345 --disable-reporting /etc/aims-alloy/config.alloy
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT

step START
systemctl daemon-reload
systemctl enable node_exporter aims-alloy >/dev/null 2>&1
systemctl restart node_exporter aims-alloy

step VERIFY
for _ in $(seq 1 30); do
  if probe http://127.0.0.1:9100/metrics && probe http://127.0.0.1:12345/-/ready; then
    echo "AIMS_AGENT_OK alloy=v${ALLOY_VERSION} node_exporter=${NODE_EXPORTER_VERSION} gpu=${GPU_STATE}"
    exit 0
  fi
  sleep 2
done
journalctl -u aims-alloy -n 20 --no-pager 2>/dev/null || true
fail "에이전트가 60초 안에 준비되지 않았습니다(aims-alloy 로그 확인)"
