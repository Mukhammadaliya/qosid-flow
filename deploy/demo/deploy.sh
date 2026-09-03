#!/usr/bin/env bash
# QosID Flow — demo instansini bir marta ko'tarish (192.168.10.85, Oracle Linux 9).
#
# Serverda ishga tushiriladi:   sudo bash deploy.sh
#
# Nima qiladi:
#   1. shart-sharoitni tekshiradi (java 21, docker, port 2021, disk)
#   2. nginx o'rnatadi (maven/npm KERAK EMAS — JAR va SPA tayyor holda keladi)
#   3. infratuzilmani ko'taradi: Redpanda, Redis, Prometheus, Grafana, Loki
#   4. .env yaratadi — sirlar SHU YERDA generatsiya qilinadi, hech qayerdan ko'chirilmaydi
#   5. systemd unit'larini qo'yadi va ikkala JVM'ni ishga tushiradi
#   6. nginx'ni 2021-portga sozlaydi
#   7. o'zini tekshiradi va admin parolini bir marta chiqaradi
#
# Idempotent: qayta ishga tushirilsa mavjud .env va parolni SAQLAB QOLADI.

set -euo pipefail

APP_DIR=/opt/services/qosid-flow
SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

say()  { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()   { printf '   \033[32mOK\033[0m  %s\n' "$*"; }
bad()  { printf '   \033[31mXATO\033[0m %s\n' "$*"; }
warn() { printf '   \033[33m!\033[0m   %s\n' "$*"; }

# ------------------------------------------------------------------ 1. tekshiruv
say "1/7 Shart-sharoit"

command -v java >/dev/null || { bad "java yo'q"; exit 1; }
JV=$(java -version 2>&1 | head -1)
case "$JV" in *\"21*|*\ 21.*) ok "$JV" ;; *) bad "Java 21 kerak, topildi: $JV"; exit 1 ;; esac

command -v docker >/dev/null || { bad "docker yo'q"; exit 1; }
docker info >/dev/null 2>&1 || { bad "docker demoni ishlamayapti"; exit 1; }
ok "$(docker --version)"

docker compose version >/dev/null 2>&1 || { bad "'docker compose' plagini yo'q"; exit 1; }
ok "$(docker compose version | head -1)"

if ss -tln 2>/dev/null | grep -q ':2021 '; then bad "2021 porti band"; exit 1; fi
ok "2021 porti bo'sh"

FREE_GB=$(df -BG --output=avail / | tail -1 | tr -dc '0-9')
if [ "$FREE_GB" -lt 20 ]; then bad "diskda ${FREE_GB}G bo'sh joy — kamida 20G kerak"; exit 1; fi
ok "diskda ${FREE_GB}G bo'sh"

# Bu mashinada ClickHouse va Superset ishlaydi. Redpanda diskni to'ldirsa ULAR HAM
# yiqiladi — 2026-05-18 da aynan shunday 57 GB volume to'lib tizim 34 soat o'lgan.
# Shuning uchun pastda Redpanda uchun retention chegarasi majburan qo'yiladi.
if docker ps --format '{{.Names}}' | grep -qE 'clickhouse|superset'; then
  warn "bu serverda ClickHouse/Superset ishlab turibdi — Redpanda retention cheklanadi"
fi

# ------------------------------------------------------------------ 2. nginx
say "2/7 nginx"
if command -v nginx >/dev/null; then
  ok "nginx allaqachon bor: $(nginx -v 2>&1)"
else
  dnf install -y nginx >/dev/null 2>&1 || { bad "nginx o'rnatilmadi (internet yo'qmi?)"; exit 1; }
  ok "nginx o'rnatildi"
fi

# ------------------------------------------------------------------ 3. fayllar
say "3/7 Fayllarni joylash"
mkdir -p "$APP_DIR"/{data,logs,frontend}
cp -f "$SRC_DIR/app.jar" "$APP_DIR/app.jar"
cp -f "$SRC_DIR/ops.jar" "$APP_DIR/ops.jar"
rm -rf "$APP_DIR/frontend/dist"
cp -r "$SRC_DIR/dist" "$APP_DIR/frontend/dist"
cp -f "$SRC_DIR/docker-compose.yml" "$APP_DIR/docker-compose.yml"

# infrastructure/ va docker/ SHU YERGA ko'chirilishi SHART. docker-compose.yml ularni
# nisbiy yo'l bilan bind-mount qiladi (./infrastructure/prometheus/prometheus.yml,
# ./docker/loki-config.yml, ...) va compose $APP_DIR dan ishga tushadi. Agar manba fayl
# yo'q bo'lsa, Docker uni O'CHIRIB QO'YMAYDI — o'rniga PAPKA yaratadi, keyin mount
# "not a directory" bilan yiqiladi va konteyner ko'tarilmaydi. Birinchi urinishda aynan
# shu bo'ldi, shuning uchun pastda yolg'on papkalar avval tozalanadi.
for f in infrastructure/prometheus/prometheus.yml infrastructure/prometheus/gateway-alerts.yml \
         infrastructure/alertmanager/alertmanager.yml docker/loki-config.yml docker/promtail-config.yml; do
  [ -d "$APP_DIR/$f" ] && { rm -rf "$APP_DIR/$f"; warn "Docker yaratgan yolg'on papka o'chirildi: $f"; }
done
cp -r "$SRC_DIR/infrastructure" "$APP_DIR/"
cp -r "$SRC_DIR/docker" "$APP_DIR/"
ok "app.jar, ops.jar, frontend/dist, docker-compose.yml, infrastructure/, docker/"

# Mount manbalari haqiqatan FAYL ekanini tasdiqlaymiz — compose'ni ishga tushirgandan
# keyin bilib olish qimmat, chunki Docker qayta papka yaratadi.
for f in infrastructure/prometheus/prometheus.yml infrastructure/alertmanager/alertmanager.yml \
         docker/loki-config.yml docker/promtail-config.yml; do
  [ -f "$APP_DIR/$f" ] || { bad "mount manbasi fayl emas: $f"; exit 1; }
done
ok "barcha bind-mount manbalari fayl sifatida joyida"

# ------------------------------------------------------------------ 4. .env
say "4/7 Konfiguratsiya"
if [ -f "$APP_DIR/.env" ]; then
  warn ".env allaqachon bor — SAQLANADI (sirlar qayta generatsiya qilinmaydi)"
  ADMIN_PW=""
else
  # Sirlar shu yerda tug'iladi va shu mashinadan chiqmaydi.
  ENC_KEY=$(openssl rand -hex 32)
  ADMIN_PW=$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-16)

  cat > "$APP_DIR/.env" <<ENVEOF
# QosID Flow — DEMO instansi. deploy.sh tomonidan generatsiya qilingan.
SERVER_PORT=8090
MANAGEMENT_PORT=8091
SPRING_PROFILES_ACTIVE=prod
LOG_LEVEL=INFO

# --- DEMO: manba tizimi ULANMAGAN ---
# Pull o'chirilgan va credentiallar bo'sh. Bu ataylab: mashina ommaviy domenga
# chiqadi, ya'ni unga prod credentiallarini qo'yish kerak emas. Namoyish qilinadigan
# narsa — konsol (statistika, xatolar, ops) va REST proksi.
POLLING_ENABLED=false
SOURCE_BASE_URL=
SOURCE_PULL_URI=
SOURCE_SAVE_URI=
SOURCE_OAUTH2_PROVIDER_URI=

# REST proksining upstream'i. Demo uchun ommaviy echo xizmati; o'zgartirish mumkin.
API_BASE_URL=https://httpbin.org

KAFKA_BOOTSTRAP_SERVERS=localhost:19092
KAFKA_GROUP_ID=gateway-service-group
KAFKA_PARTITIONS=10
REDIS_HOST=localhost
REDIS_PORT=6379

# nginx bilan bir hostda -> 127.0.0.1 to'g'ri. Kengroq qo'yish mijozga o'z IP'sini
# soxtalashtirish imkonini beradi.
TRUSTED_PROXIES=127.0.0.1
PROXY_MAX_BODY_SIZE=20971520

# TLS oldingi proksida tugatiladi, ya'ni bu yerga so'rov HTTP bo'lib keladi va
# Tomcat o'zi Secure qo'ymaydi. Busiz sessiya cookie'lari HTTPS'da himoyalanmagan qoladi.
COOKIE_SECURE=true

ROCKSDB_PATH=$APP_DIR/data/rocksdb
ROCKSDB_RETENTION_DAYS=7
S3_ENABLED=false

SETTINGS_DB_PATH=$APP_DIR/data/settings
SETTINGS_ENCRYPTION_KEY=$ENC_KEY
# Bir martalik: birinchi boot'da admin parolini shu qiymatga qo'yadi. Keyin bu
# satr olib tashlanadi (pastda), aks holda har restartda parol qaytadan tiklanadi.
ADMIN_PASSWORD_RESET=$ADMIN_PW

# Demo'da DLQ alertlari kerak emas.
TELEGRAM_ENABLED=false

LOKI_URL=http://localhost:3100
PROMETHEUS_URL=http://localhost:9090

OPS_PORT=8092
OPS_GATEWAY_HEALTH_URL=http://localhost:8090/qosid/admin/api/health
OPS_GATEWAY_START_COMMAND=/usr/bin/systemctl start gateway-service
OPS_GATEWAY_STOP_COMMAND=/usr/bin/systemctl stop gateway-service
OPS_LOG_FILE=$APP_DIR/logs/gateway.log
OPS_SETTINGS_DB_PATH=$APP_DIR/data/settings
ENVEOF
  chmod 600 "$APP_DIR/.env"
  ok ".env yaratildi (0600), sirlar shu mashinada generatsiya qilindi"
fi

# ------------------------------------------------------------------ 5. infra
say "5/7 Infratuzilma (docker compose)"
cd "$APP_DIR"
# `set -o pipefail` yoqilgan, ya'ni `docker compose ... | tail` yiqilsa BUTUN skript
# to'xtaydi va 6/7-qadamlar (JVM'lar, nginx) umuman bajarilmaydi — birinchi urinishda
# aynan shu bo'ldi. Shuning uchun holatni alohida ushlaymiz: bitta konteyner ko'tarilmasa
# ogohlantiramiz, lekin deploy'ni to'xtatmaymiz — gateway Redpanda va Redis bilan ishlaydi,
# Prometheus/Grafana esa faqat kuzatuv paneli.
COMPOSE_LOG=$(mktemp)
if docker compose up -d > "$COMPOSE_LOG" 2>&1; then
  ok "barcha konteynerlar ko'tarildi"
else
  warn "ba'zi konteynerlar ko'tarilmadi — quyida sabab; deploy davom etadi"
  grep -iE "error|failed" "$COMPOSE_LOG" | head -4 | sed 's/^/       /'
fi
rm -f "$COMPOSE_LOG"
sleep 8

# Redpanda retention — ClickHouse/Superset bilan bir diskda yashaymiz.
if docker ps --format '{{.Names}}' | grep -q '^redpanda$'; then
  docker exec redpanda rpk cluster config set retention_bytes 2147483648 >/dev/null 2>&1 \
    && ok "Redpanda retention 2 GB ga cheklandi" \
    || warn "retention_bytes qo'yilmadi — diskni qo'lda kuzatib turing"
  docker exec redpanda rpk cluster config set kafka_batch_max_bytes 20971520 >/dev/null 2>&1 \
    && ok "kafka_batch_max_bytes 20 MB" || true
fi
docker compose ps --format 'table {{.Name}}\t{{.State}}' 2>/dev/null | head -12

# ------------------------------------------------------------------ 6. systemd
say "6/7 systemd unit'lari"

# Ikkala JVM bitta .env ni o'qiydi, va u yerda SERVER_PORT=8090 bor. Spring Boot muhit
# o'zgaruvchisini `server.port` ga bog'laydi va muhit o'zgaruvchisi application.yml dagi
# `${OPS_PORT:8092}` dan USTUN turadi — natijada ops sidecar 8090 ni egallashga urinadi va
# "Port 8090 was already in use" bilan yiqiladi. Demo serverida bu 30 marta takrorlandi.
# Shuning uchun ops uchun alohida override fayl: u SERVER_PORT ni OPS_PORT bilan bosib o'tadi.
printf 'SERVER_PORT=%s
' "$(grep -E '^OPS_PORT=' "$APP_DIR/.env" | cut -d= -f2)" > "$APP_DIR/ops.env"
chmod 600 "$APP_DIR/ops.env"
ok "ops.env — ops uchun SERVER_PORT override"

for u in gateway-service qosid-ops; do
  jar=app.jar; port_desc="gateway :8090"; extra_env=""
  [ "$u" = qosid-ops ] && { jar=ops.jar; port_desc="ops sidecar :8092"; extra_env="EnvironmentFile=$APP_DIR/ops.env"; }
  cat > /etc/systemd/system/$u.service <<UNITEOF
[Unit]
Description=QosID Flow — $port_desc (demo)
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=$APP_DIR
EnvironmentFile=$APP_DIR/.env
$extra_env
ExecStart=/usr/bin/java -jar $APP_DIR/$jar
Restart=on-failure
RestartSec=5
# journal, `append:` EMAS. logback allaqachon logs/gateway.log ni hajm chegarasi bilan
# rotatsiya qiladi; systemd ayni oqimni ikkinchi faylga ROTATSIYASIZ yozadi. Demo serverida
# shu ikkinchi nusxa uch kunda 4.7 GB ga yetdi (Kafka o'lgan va NetworkClient sekundiga ~44
# satr yozgan payt). journald o'z hajm chegarasiga ega va `journalctl --vacuum-*` bilan
# tozalanadi. Ops konsolining jonli log oynasi logback fayliga qaraydi (OPS_LOG_FILE),
# shuning uchun bu o'zgarish unga ta'sir qilmaydi.
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
UNITEOF
done
systemctl daemon-reload
ok "gateway-service.service, qosid-ops.service"

systemctl enable --now gateway-service >/dev/null 2>&1 || true
# Gateway birinchi bo'lib ko'tarilishi kerak: admin_account jadvalini u yaratadi,
# ops esa shu jadvalga autentifikatsiya qiladi.
say "    gateway ko'tarilishini kutaman"
for i in $(seq 60); do
  if curl -sf -m 3 http://localhost:8090/qosid/admin/api/health >/dev/null 2>&1; then break; fi
  sleep 2
done
curl -sf -m 3 http://localhost:8090/qosid/admin/api/health >/dev/null 2>&1 \
  && ok "gateway UP" || { bad "gateway ko'tarilmadi — journalctl -u gateway-service ga qarang"; journalctl -u gateway-service -n 25 --no-pager 2>/dev/null; exit 1; }

# Parol tiklandi; satrni olib tashlaymiz, aks holda har restart uni qaytaradi.
if [ -n "$ADMIN_PW" ]; then
  sed -i '/^ADMIN_PASSWORD_RESET=/d' "$APP_DIR/.env"
  ok "ADMIN_PASSWORD_RESET .env dan olib tashlandi (bir martalik)"
fi

systemctl enable --now qosid-ops >/dev/null 2>&1 || true
sleep 5
curl -sf -m 3 http://localhost:8092/ops/api/status >/dev/null 2>&1 \
  && ok "ops sidecar UP" || { warn "ops javob bermadi — quyida oxirgi satrlar"; journalctl -u qosid-ops -n 15 --no-pager 2>/dev/null; }

# ------------------------------------------------------------------ 7. nginx
say "7/7 nginx 2021"
cp -f "$SRC_DIR/nginx-qosid-2021.conf" /etc/nginx/conf.d/qosid.conf
# SELinux Oracle Linux'da yoqilgan: usiz nginx 8090/8092 ga ulanolmaydi (403/502)
# va dist'ni o'qiy olmaydi.
setsebool -P httpd_can_network_connect 1 2>/dev/null && ok "SELinux: httpd_can_network_connect" || true
chcon -R -t httpd_sys_content_t "$APP_DIR/frontend/dist" 2>/dev/null || true
nginx -t 2>&1 | tail -2
systemctl enable --now nginx >/dev/null 2>&1 || true
systemctl reload nginx 2>/dev/null || systemctl restart nginx
ok "nginx 2021 da"

# ------------------------------------------------------------------ tekshiruv
say "Tekshiruv"
check() { printf '   %-46s ' "$1"; c=$(curl -s -o /dev/null -m 8 -w '%{http_code}' "$2" 2>/dev/null || echo 000); [ "$c" = "$3" ] && printf '\033[32m%s\033[0m\n' "$c" || printf '\033[31m%s (kutilgan %s)\033[0m\n' "$c" "$3"; }
check "SPA (nginx statik)"                 "http://localhost:2021/"                            200
check "gateway admin health (nginx orqali)" "http://localhost:2021/qosid/admin/api/health"     200
check "ops status (nginx orqali)"          "http://localhost:2021/ops/api/status"              200
check "admin sozlamalari (auth talab qiladi)" "http://localhost:2021/qosid/admin/api/settings" 401
check "aktuator TASHQARIDAN yopiqmi"       "http://localhost:2021/actuator/health"             404

if [ -n "$ADMIN_PW" ]; then
  printf '\n\033[1;33m╔══════════════════════════════════════════════════════════╗\033[0m\n'
  printf '\033[1;33m║  ADMIN PAROLI (bir marta ko'"'"'rsatiladi, saqlab qo'"'"'ying)     ║\033[0m\n'
  printf '\033[1;33m║  login: admin   parol: %-32s ║\033[0m\n' "$ADMIN_PW"
  printf '\033[1;33m╚══════════════════════════════════════════════════════════╝\033[0m\n'
fi

printf '\n   Tashqi manzil: https://qosid.greenwhite.uz\n'
printf '   Loglar:        %s/logs/\n\n' "$APP_DIR"
