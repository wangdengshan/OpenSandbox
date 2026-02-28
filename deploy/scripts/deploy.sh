#!/usr/bin/env bash
# =============================================================================
# OpenSandbox Kubernetes 部署脚本
# 适用于: kind 集群 (单机快速部署)
# 用法: bash scripts/deploy.sh [KIND_CLUSTER_NAME]
# =============================================================================

set -e

CLUSTER_NAME="${1:-opensandbox-cluster}"
KIND_NODE_IP=""
INGRESS_NODEPORT=""
NAMESPACE_SYSTEM="opensandbox-system"
NAMESPACE_SANDBOX="opensandbox"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(dirname "$SCRIPT_DIR")"

# ---- 颜色输出 ----
info()  { echo -e "\033[32m[INFO]\033[0m  $*"; }
warn()  { echo -e "\033[33m[WARN]\033[0m  $*"; }
error() { echo -e "\033[31m[ERROR]\033[0m $*" >&2; exit 1; }

# ---- Step 0: 前置检查 ----
info "Step 0: Checking prerequisites..."
command -v kubectl >/dev/null 2>&1 || error "kubectl not found"
command -v kind    >/dev/null 2>&1 || error "kind not found"
command -v docker  >/dev/null 2>&1 || error "docker not found"

# ---- Step 1: 创建 kind 集群 ----
info "Step 1: Creating kind cluster '$CLUSTER_NAME'..."
if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
    warn "Cluster '$CLUSTER_NAME' already exists, skipping creation."
else
    kind create cluster --name "$CLUSTER_NAME"
fi
kubectl cluster-info --context "kind-${CLUSTER_NAME}" >/dev/null

# ---- Step 2: 构建并加载 Operator 镜像 ----
info "Step 2: Building OpenSandbox operator images..."
REPO_ROOT="$(dirname "$DEPLOY_DIR")"
GOBIN_PATH="/usr/local/go1.24/bin"
if [ ! -f "${GOBIN_PATH}/go" ]; then
    warn "Go 1.24 not found at ${GOBIN_PATH}, trying system go..."
    GOBIN_PATH=""
fi

export PATH="${GOBIN_PATH}:${PATH}"
export GOMODCACHE="${HOME}/go/pkg/mod"

# Build controller
info "  Building controller binary..."
(cd "${REPO_ROOT}/kubernetes" && go build -o /tmp/opensandbox-controller ./cmd/controller/)

# Build task-executor
info "  Building task-executor binary..."
(cd "${REPO_ROOT}/kubernetes" && go build -o /tmp/opensandbox-task-executor ./cmd/task-executor/)

# Build Docker images
info "  Building controller Docker image..."
cp /tmp/opensandbox-controller "${REPO_ROOT}/kubernetes/server"
docker build -f /tmp/Dockerfile.operator -t opensandbox-controller:local "${REPO_ROOT}/kubernetes"
rm -f "${REPO_ROOT}/kubernetes/server"

info "  Building task-executor Docker image..."
cp /tmp/opensandbox-task-executor "${REPO_ROOT}/kubernetes/task-executor"
docker build -f /tmp/Dockerfile.taskexecutor -t opensandbox-task-executor:local "${REPO_ROOT}/kubernetes"
rm -f "${REPO_ROOT}/kubernetes/task-executor"

# Write Dockerfiles for reference
cat > /tmp/Dockerfile.operator << 'EOF'
FROM debian:bullseye-slim
RUN useradd -u 65532 nonroot 2>/dev/null || true
WORKDIR /workspace
COPY server .
USER 65532
ENTRYPOINT ["/workspace/server"]
EOF

cat > /tmp/Dockerfile.taskexecutor << 'EOF'
FROM debian:bullseye-slim
WORKDIR /workspace
COPY task-executor server
RUN chmod +x /workspace/server
ENTRYPOINT ["/workspace/server"]
EOF

# ---- Step 3: 拉取并加载所有镜像到 kind ----
info "Step 3: Loading images into kind cluster..."
docker pull sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/server:latest
docker pull sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/execd:v1.0.6
docker pull sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/task-executor:latest
docker pull sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/code-interpreter:v1.0.1

kind load docker-image opensandbox-controller:local                                                         --name "$CLUSTER_NAME"
kind load docker-image opensandbox-task-executor:local                                                      --name "$CLUSTER_NAME"
kind load docker-image sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/server:latest           --name "$CLUSTER_NAME"
kind load docker-image sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/execd:v1.0.6            --name "$CLUSTER_NAME"
kind load docker-image sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/task-executor:latest    --name "$CLUSTER_NAME"
kind load docker-image sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/code-interpreter:v1.0.1 --name "$CLUSTER_NAME"

# ---- Step 4: 安装 CRD ----
info "Step 4: Applying CRDs..."
kubectl apply -f "${DEPLOY_DIR}/crds/"

# ---- Step 5: 部署 Operator ----
info "Step 5: Deploying OpenSandbox operator..."
kubectl apply -f "${DEPLOY_DIR}/operator/namespace.yaml"
kubectl apply -f "${DEPLOY_DIR}/operator/rbac.yaml"
kubectl apply -f "${DEPLOY_DIR}/operator/manager.yaml"

# ---- Step 6: 安装 ingress-nginx ----
info "Step 6: Installing ingress-nginx..."
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
info "  Waiting for ingress-nginx controller to be ready..."
kubectl wait --namespace ingress-nginx \
    --for=condition=ready pod \
    --selector=app.kubernetes.io/component=controller \
    --timeout=120s

# ---- Step 7: 部署 Server ----
info "Step 7: Deploying OpenSandbox server..."
kubectl apply -f "${DEPLOY_DIR}/server/server-deployment.yaml"
kubectl apply -f "${DEPLOY_DIR}/ingress/ingress.yaml"

# ---- Step 8: 创建 Pool ----
info "Step 8: Creating code-interpreter pool..."
kubectl apply -f "${DEPLOY_DIR}/pool/pool.yaml"

# ---- Step 9: 配置本机 nginx ----
info "Step 9: Configuring host nginx..."
KIND_NODE_IP=$(docker inspect "${CLUSTER_NAME}-control-plane" | \
    python3 -c "import json,sys; n=json.load(sys.stdin)[0]; nets=n['NetworkSettings']['Networks']; [print(v['IPAddress']) for v in nets.values() if v.get('IPAddress')]" 2>/dev/null | head -1)

INGRESS_NODEPORT=$(kubectl get svc -n ingress-nginx ingress-nginx-controller \
    -o jsonpath='{.spec.ports[?(@.name=="http")].nodePort}')

info "  Kind node IP: ${KIND_NODE_IP}, Ingress NodePort: ${INGRESS_NODEPORT}"

# 更新 nginx 配置里的 IP 和端口
sed "s|172.18.0.2:30574|${KIND_NODE_IP}:${INGRESS_NODEPORT}|g" \
    "${DEPLOY_DIR}/nginx/opensandbox.conf" > /etc/nginx/conf.d/opensandbox.conf

# 添加 hosts 条目
if ! grep -q "opensandbox.local" /etc/hosts; then
    echo "127.0.0.1 opensandbox.local" >> /etc/hosts
    info "  Added opensandbox.local to /etc/hosts"
fi

nginx -t && systemctl reload nginx
info "  Nginx reloaded"

# ---- Step 10: 等待 Server 就绪 ----
info "Step 10: Waiting for opensandbox-server to be ready..."
kubectl wait --namespace "${NAMESPACE_SANDBOX}" \
    --for=condition=ready pod \
    --selector=app=opensandbox-server \
    --timeout=120s

# ---- 验证 ----
info "Verifying deployment..."
HEALTH=$(curl -sf http://localhost:8080/health 2>/dev/null || echo "FAILED")
if echo "$HEALTH" | grep -q "healthy"; then
    info "✓ OpenSandbox server is healthy at http://localhost:8080"
else
    error "Health check failed: ${HEALTH}"
fi

info "=== Deployment complete ==="
info ""
info "SDK Usage:"
info "  uv venv /root/opensandbox-venv --python python3.12"
info "  source /root/opensandbox-venv/bin/activate"
info "  uv pip install opensandbox opensandbox-code-interpreter"
info "  SANDBOX_DOMAIN=localhost:8080 python3 examples/code-interpreter/main.py"
