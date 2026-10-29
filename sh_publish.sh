#!/usr/bin/env bash
set -euo pipefail

workDir=$(cd "$(dirname "$0")" && pwd)

# ================= 用法 =================
if [ $# -lt 1 ]; then
  cat <<USAGE
用法: $(basename "$0") <apk本地路径>

将指定 APK 发布到:

  1. Google Cloud Storage (bucket: manager-strorage1)
     key    : v3/apk/<时间戳>-diplay<随机6位>.apk
             (与 backend_oadmin 应用市场 APK 同一目录、同一命名规则)
     访问   : https://gp.7654321.org/manager-strorage1/v3/apk/<key>

  2. 调用 RPC 更新远端应用信息 (appId=2)
     从 APK 提取 versionCode / versionName, url 取 GCS 的 key (v3/apk/xxx.apk)

依赖: python3 / openssl / curl / Android SDK 的 aapt
USAGE
  exit 1
fi

APK="$1"
if [ ! -f "$APK" ]; then
  printf '\033[31m😵 APK 文件不存在: %s\033[0m\n' "$APK"
  exit 1
fi
APK="$(cd "$(dirname "$APK")" && pwd)/$(basename "$APK")"

for c in python3 openssl curl; do
  if ! command -v "$c" >/dev/null 2>&1; then
    printf '\033[31m😵 缺少依赖命令: %s\033[0m\n' "$c"
    exit 1
  fi
done

# ================= GCS 配置 =================
GCS_BUCKET="manager-strorage1"
GCS_CDN_URL="https://gp.7654321.org/manager-strorage1"
# 与 backend_oadmin/config/config.default.ts 中 gcs.serviceAccount.client_email 一致
GCS_CLIENT_EMAIL="ec1-764@manager-508416.iam.gserviceaccount.com"

# ========== 1. 上传 GCS ==========
GCS_STAMP="$(date +%Y%m%d%H%M%S)"
# 注意: 不能用 tr | head -c 生成随机串(配合 set -o pipefail 会因 SIGPIPE 返回 141 直接中断)
GCS_RAND="$(python3 -c 'import random,string; print("".join(random.choices(string.ascii_lowercase + string.digits, k=6)))')"
GCS_KEY="v3/apk/${GCS_STAMP}-diplay${GCS_RAND}.apk"

echo "==> [1/2] 上传 GCS: $GCS_BUCKET / $GCS_KEY"

# 服务账号私钥(PEM): 与 backend_oadmin/config/config.default.ts 中 gcs.serviceAccount.private_key 一致
GCS_KEY_FILE="$(mktemp "${TMPDIR:-/tmp}/gcs_key.XXXXXX")"
trap 'rm -f "$GCS_KEY_FILE"' EXIT
cat > "$GCS_KEY_FILE" <<'GCSPEM'
-----BEGIN PRIVATE KEY-----
MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQC9JK6XAWua+QLC
Rbyap73Q8OyqAGxDG9GY5jYW1Y0is5tMwSddutCwCRWH0yYP64Uq3BqzBgZ9LYCG
G+/eQ56lCvGE6C+xLmtVbuiDYslDWX1hCsFmMrdzxSYmwfTbe6pc9Naq6ujPeXvP
g0Eg3CGsce4k+bvHnhh6tfd/5PiVSkGkFg791Do7hIYQwkvSPp8dRAtodoKhhMlH
1se5NGDbKVgqORa2a5oo7FzePbUHtyYQ7UHKecZlSEO0YHmAaNQJJyOSSk5aAA7D
e0Us2oI61d63zOQVy7Ynlp8y9ErQFRAN9ZT6cwmVAcRHv9W/9OBBA7v3FRhQV1wh
DkDhoUMFAgMBAAECgf8jNieh9RIfuykGw0X1a7VIT8O/TG3U8T/h5ebnOh7/tHyS
iMCM8Ok6AdSWI3Q3N5TXqnmY5uPqdlwluoRXZc7eSHxvhmY7UUm8z9YuegOTvmZg
ybjHAuiB0KJo0yl9ssA2dPpJkraMfzzxS9WC6uJgMdvMV5JfTC6HJpFQzY4PaEVP
r3czA3LX0cEg4dFxGz+qfxlqCcAMj3no+i+89AYUXtpdPXrRcjrGn18d+OmByd1j
lyQFEIa1oyTOCqjn5NhnCR+8Br5KWDKaQaRC+DVO/Nl6QSLX+cPERsBZI+0R4qqH
JHTr4uDXqU5sGXICt3ICuxldLEj9u7KLtnNR69kCgYEA+DiMxBiAPXLmoQhI2DmK
Ehnz68p42dfstRRrPJNETDLmQszgu6dX1ogbfNswqBA9QnRzKXa2q4I2w/JK85El
5+R5iW6FPQQ+d78d8J7tk0FRiYQ+GWppElLwAiny0UGFDEf/nEktH+hedH4s9Flu
1GK8hip+O8BbD/xGNvDL+jsCgYEAwxIon8G9P5XJEGq4wYM7MRW2qFnH+lVrdtDS
AFybJzdOlPSO6MOeQDVDHOP86PvV1B1wLMNUvcVeA0dgdMq/aV85tbVN5ju8nCYV
y50/C1oRw6LA8uNAc/QipsgUs6VSzvtlBbaBa6fnsAase+3oWKp/nIdDU21MtY1a
qbt6o78CgYEApkZTXWTAOumGPqbrDRT2PYvvDqtR6zOuV9q2vxwzawvJnUPa4zmF
KKuCJlA6NsqyDpstLcBdO2R9xSLAPIqlpsIw+/PyAt6d7d8zciU0JiliMklIxAO5
INI3/msVQkGum3x7aLjIJpCk/UosWP6ztEfz27gJWYNQPNQUQOdq7FMCgYEAp/2g
XIxlJTgiMnAl8gjI1emkJvFCpdQgOu90mnkl6KzZkK/Xzp6ATz1PMbcpm58dM5Gy
g+ontVIeN2alq47bLqyiL1kW3/p74GFwDay6mVUUzU0BK2sHm5QYr1bJdTgfyz52
bmgQppyvi0kEC6oDmTAju7JFCbisAgHcnSVmZVMCgYBM63pzDnJwI3pY88yaHP4V
aW0R7Z1FPTrjy+z97HM6qP+Pvg4Vn45DOwxs4dlPOKfPbFkCxZupieenFLuMjQX/
yYT2sjCKEqOOy+lwqAqa+55Z/QqwcPeaSprSw7LkRBRGB84fhZMd8XXeMle3zN/v
dtnDJtvq3e4qjIGd0KfoVQ==
-----END PRIVATE KEY-----

GCSPEM

python3 - "$APK" "$GCS_BUCKET" "$GCS_KEY" "$GCS_KEY_FILE" "$GCS_CDN_URL" "$GCS_CLIENT_EMAIL" <<'PYEOF'
import sys, hashlib, base64, subprocess, urllib.request, urllib.error, urllib.parse, datetime

apk, bucket, key, key_file, cdn_url, client_email = sys.argv[1:7]

now = datetime.datetime.now(datetime.timezone.utc)
timestamp = now.strftime("%Y%m%dT%H%M%SZ")
scope_date = now.strftime("%Y%m%d")
expires = "900"

def pct(s):
    return urllib.parse.quote(s, safe="-_.~")

host = "storage.googleapis.com"
credential = client_email + "/" + scope_date + "/auto/storage/goog4_request"
query = [
    ("X-Goog-Algorithm", "GOOG4-RSA-SHA256"),
    ("X-Goog-Credential", credential),
    ("X-Goog-Date", timestamp),
    ("X-Goog-Expires", expires),
    ("X-Goog-SignedHeaders", "host;x-goog-acl"),
]
canonical_query = "&".join(pct(k) + "=" + pct(v) for k, v in query)
path = "/" + bucket + "/" + key
canonical_request = "\n".join([
    "PUT",
    path,
    canonical_query,
    "host:" + host + "\n" + "x-goog-acl:public-read\n",
    "host;x-goog-acl",
    "UNSIGNED-PAYLOAD",
])
string_to_sign = "\n".join([
    "GOOG4-RSA-SHA256",
    timestamp,
    scope_date + "/auto/storage/goog4_request",
    hashlib.sha256(canonical_request.encode()).hexdigest(),
])

# 离线用 openssl 对 string_to_sign 做 RSA-SHA256 签名
p = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key_file],
                   input=string_to_sign.encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
if p.returncode != 0:
    sys.stderr.write("   openssl 签名失败: " + p.stderr.decode() + "\n")
    sys.exit(1)
# 关键: X-Goog-Signature 必须用签名原始字节的 HEX 编码(512 个小写 hex 字符),
# 与 @google-cloud/storage 的 signer.js 一致: Buffer.from(sig,'base64').toString('hex'),
# 而不是 base64 字符串
signature = p.stdout.hex()

# 主机/桶路径前缀替换为国内 CDN 代理域名(nginx 原样转发路径并还原 Host 为 storage.googleapis.com)
signed_url = ("https://" + host + path + "?" + canonical_query
              + "&X-Goog-Signature=" + urllib.parse.quote(signature, safe=""))
path_style = "https://" + host + "/" + bucket
put_url = signed_url.replace(path_style, cdn_url.rstrip("/"), 1)

with open(apk, "rb") as f:
    data = f.read()

req = urllib.request.Request(put_url, data=data, method="PUT")
req.add_header("Content-Type", "application/vnd.android.package-archive")
req.add_header("x-goog-acl", "public-read")
try:
    resp = urllib.request.urlopen(req, timeout=600)
    print("   上传成功 HTTP", resp.status)
except urllib.error.HTTPError as e:
    sys.stderr.write("   GCS 上传失败 HTTP %s: %s\n" % (e.code, e.read().decode("utf-8", "replace")))
    sys.exit(1)
PYEOF
echo "   ✅ GCS: $GCS_CDN_URL/$GCS_KEY"

# ========== 2. 更新远端应用信息(RPC) ==========
# 从 APK 提取 versionCode / versionName
AAPT=""
for bt in "$HOME/Library/Android/sdk/build-tools" "${ANDROID_HOME:-}/build-tools" "${ANDROID_SDK_ROOT:-}/build-tools"; do
    if [ -d "$bt" ]; then
        AAPT="$(find "$bt" -maxdepth 2 -type f -name aapt 2>/dev/null | sort -V | tail -1)"
        if [ -n "$AAPT" ]; then
            break
        fi
    fi
done
if [ -z "$AAPT" ]; then
    printf '\033[31m😵 未找到 Android SDK 的 aapt,无法从 APK 提取版本信息\033[0m\n'
    exit 1
fi

badging_all="$("$AAPT" dump badging "$APK" 2>/dev/null)"
badging="$(printf '%s\n' "$badging_all" | grep -m1 '^package:')"
versionCode="$(printf '%s' "$badging" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")"
versionName="$(printf '%s' "$badging" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
if [ -z "$versionCode" ] || [ -z "$versionName" ]; then
    printf '\033[31m😵 从 APK 提取 versionCode / versionName 失败\033[0m\n'
    exit 1
fi

echo "==> [2/2] 更新远端应用信息(RPC): appId=2 versionCode=$versionCode versionName=$versionName"
RPC_URL="https://oadmin.7654321.org/api/rpc/app/update_apk"
# url 传 GCS 对象 key(纯路径 v3/apk/xxx.apk,不含桶名/域名):
# 与 commitUpload 一致(commitUpload 存的是 apkKey = v3/apk/xxx.apk),后端 keyOf 对无 scheme 的 key 原样存储
rpc_payload="$(printf '{"secret":"apkupdate6688@!","appId":2,"versionCode":%s,"versionName":"%s","url":"%s"}' \
    "$versionCode" "$versionName" "$GCS_KEY")"
echo "   请求: $rpc_payload"
# curl 传输失败(网络错误)或 HTTP>=400(如 404 未部署)时立即报错退出,并把响应正文一并打印
rpc_resp="$(curl -sS -f -X POST "$RPC_URL" -H 'Content-Type: application/json' -d "$rpc_payload")" || {
    printf '\033[31m😵 RPC 请求失败(HTTP 错误/网络不可达): %s\033[0m\n' "$rpc_resp"
    exit 1
}
echo "   响应: $rpc_resp"

# 后端业务码 code 必须为 20000(successRes),否则视为更新失败并报错退出
rpc_code="$(printf '%s' "$rpc_resp" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("code"))' 2>/dev/null || true)"
if [ "$rpc_code" != "20000" ]; then
    printf '\033[31m😵 RPC 更新失败(业务码 %s): %s\033[0m\n' "${rpc_code:-未知}" "$rpc_resp"
    exit 1
fi

echo ""
echo "发布完成:"
echo "  GCS : $GCS_CDN_URL/$GCS_KEY"
echo "  RPC : appId=2 versionCode=$versionCode versionName=$versionName"