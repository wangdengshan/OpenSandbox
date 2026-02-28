# OpenSandbox Kubernetes 部署文档

本目录包含将 OpenSandbox 部署到 Kubernetes（kind 本机集群）所需的全部文件。

## 目录结构

```
deploy/
├── crds/                         # Kubernetes CRD 定义
│   ├── sandbox.opensandbox.io_batchsandboxes.yaml
│   └── sandbox.opensandbox.io_pools.yaml
├── operator/                     # Operator 控制器部署
│   ├── namespace.yaml            # opensandbox / opensandbox-system 命名空间
│   ├── rbac.yaml                 # ServiceAccount + ClusterRole + RoleBinding
│   └── manager.yaml              # Operator Deployment
├── server/                       # OpenSandbox Server 部署
│   ├── server-deployment.yaml    # SA + ClusterRole + ConfigMap + Deployment + Service
│   ├── config.toml               # Server 配置参考（已内嵌入 ConfigMap）
│   └── batchsandbox-template.yaml # BatchSandbox Pod 模板参考
├── ingress/
│   └── ingress.yaml              # Ingress 规则 (host: opensandbox.local)
├── pool/
│   └── pool.yaml                 # code-interpreter Pool 资源
├── nginx/
│   └── opensandbox.conf          # 宿主机 nginx 配置（8080 + 80 端口）
└── scripts/
    └── deploy.sh                 # 一键部署脚本
```

## 快速部署（本机 kind 集群）

### 前置条件

| 工具 | 版本要求 |
|------|---------|
| docker | 20.10+ |
| kind | 任意版本 |
| kubectl | 1.21+ |
| Go | 1.24+（用于构建 operator）|
| Python | 3.10+（用于 SDK）|
| nginx | 已安装并运行 |

### 一键部署

```bash
bash deploy/scripts/deploy.sh [CLUSTER_NAME]
# CLUSTER_NAME 默认为 opensandbox-cluster
```

### 手动步骤

#### Step 1 — 搭建 kind 集群

```bash
kind create cluster --name opensandbox-cluster
```

> 本机已有 kind 集群可直接跳过。执行 `kind get clusters` 查看现有集群。

#### Step 2 — 构建 Operator 镜像

Operator 需从源码构建（无公开预构建镜像）：

```bash
export PATH=/usr/local/go1.24/bin:$PATH
cd kubernetes/
go build -o /tmp/opensandbox-controller   ./cmd/controller/
go build -o /tmp/opensandbox-task-executor ./cmd/task-executor/

# 构建 Docker 镜像
cp /tmp/opensandbox-controller kubernetes/server
docker build -f deploy/scripts/Dockerfile.operator -t opensandbox-controller:local kubernetes/

cp /tmp/opensandbox-task-executor kubernetes/task-executor
docker build -f deploy/scripts/Dockerfile.taskexecutor -t opensandbox-task-executor:local kubernetes/
```

#### Step 3 — 加载镜像到 kind

```bash
CLUSTER=opensandbox-cluster   # 替换为你的集群名

kind load docker-image opensandbox-controller:local                                                         --name $CLUSTER
kind load docker-image opensandbox-task-executor:local                                                      --name $CLUSTER
kind load docker-image sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/server:latest           --name $CLUSTER
kind load docker-image sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/execd:v1.0.6            --name $CLUSTER
kind load docker-image sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/task-executor:latest    --name $CLUSTER
kind load docker-image sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/code-interpreter:v1.0.1 --name $CLUSTER
```

#### Step 4 — 安装 CRD

```bash
kubectl apply -f deploy/crds/
kubectl get crd | grep opensandbox
```

#### Step 5 — 部署 Operator

```bash
kubectl apply -f deploy/operator/namespace.yaml
kubectl apply -f deploy/operator/rbac.yaml
kubectl apply -f deploy/operator/manager.yaml
kubectl get pods -n opensandbox-system
```

#### Step 6 — 安装 ingress-nginx

```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
kubectl wait --namespace ingress-nginx \
    --for=condition=ready pod \
    --selector=app.kubernetes.io/component=controller \
    --timeout=120s
```

#### Step 7 — 部署 Server + Service + Ingress

```bash
kubectl apply -f deploy/server/server-deployment.yaml
kubectl apply -f deploy/ingress/ingress.yaml
kubectl get pods -n opensandbox
kubectl get svc  -n opensandbox
kubectl get ingress -n opensandbox
```

#### Step 8 — 创建 Pool

```bash
kubectl apply -f deploy/pool/pool.yaml
kubectl get pool -n opensandbox
```

#### Step 9 — 配置宿主机 nginx

获取 kind 节点 IP 和 ingress NodePort：

```bash
KIND_NODE_IP=$(docker inspect opensandbox-cluster-control-plane \
    | python3 -c "import json,sys; n=json.load(sys.stdin)[0]; \
      nets=n['NetworkSettings']['Networks']; \
      [print(v['IPAddress']) for v in nets.values() if v.get('IPAddress')]" | head -1)

INGRESS_NODEPORT=$(kubectl get svc -n ingress-nginx ingress-nginx-controller \
    -o jsonpath='{.spec.ports[?(@.name=="http")].nodePort}')

echo "Kind node IP: $KIND_NODE_IP, Ingress NodePort: $INGRESS_NODEPORT"
```

用实际值替换 nginx 配置中的 `172.18.0.2:30574`，然后：

```bash
cp deploy/nginx/opensandbox.conf /etc/nginx/conf.d/opensandbox.conf
# 编辑替换 172.18.0.2:30574 为上面获取的 ${KIND_NODE_IP}:${INGRESS_NODEPORT}
nginx -t && systemctl reload nginx
```

添加 hosts 解析（server proxy 回调必需）：

```bash
echo "127.0.0.1 opensandbox.local" >> /etc/hosts
```

#### Step 10 — 验证

```bash
curl http://localhost:8080/health     # {"status":"healthy"}
curl http://opensandbox.local/health  # {"status":"healthy"}
```

## 运行 code-interpreter Demo

```bash
# 安装 SDK
uv venv /root/opensandbox-venv --python python3.12
source /root/opensandbox-venv/bin/activate
uv pip install opensandbox opensandbox-code-interpreter

# 运行 demo（use_server_proxy=True 已在 main.py 中配置）
SANDBOX_DOMAIN=localhost:8080 python3 examples/code-interpreter/main.py
```

预期输出：

```
=== Python example ===
[Python stdout] Hello from Python!
[Python result] {'py': '3.14.2', 'sum': 4}

=== Java example ===
[Java stdout] Hello from Java!
[Java stdout] 2 + 3 = 5
[Java result] 5

=== Go example ===
[Go stdout] Hello from Go!
3 + 4 = 7

=== TypeScript example ===
[TypeScript stdout] Hello from TypeScript!
[TypeScript stdout] sum = 6
```

## 迁移到其他 K8s 集群

将 `deploy/` 目录整体复制到目标集群环境，按以下调整执行：

1. **Step 2 跳过**：若目标集群可访问 Docker Registry，直接推送镜像即可；无需 kind load。
2. **operator/manager.yaml**：将 `image: opensandbox-controller:local` 替换为推送到 registry 的镜像地址，删除 `imagePullPolicy: Never`。
3. **pool/pool.yaml**：将 `image: opensandbox-task-executor:local` 和 `imagePullPolicy: Never` 替换为 registry 地址。
4. **server/server-deployment.yaml**：ConfigMap 中的 `batchsandbox-template.yaml` 同上处理。
5. **nginx**：根据目标集群 ingress 暴露方式（LoadBalancer IP / NodePort / 其他）调整 `proxy_pass` 地址。

## 架构图

```
外部 SDK                    宿主机                        Kind 集群
    │                          │                               │
    │  SANDBOX_DOMAIN=         │                               │
    │  localhost:8080  ──────▶ │ nginx:8080                   │
    │                          │   └──proxy_pass──▶ 172.18.0.2:30574
    │  use_server_proxy=True   │                     ingress-nginx
    │                          │                          │
    │  proxy endpoint:         │                          ▼
    │  opensandbox.local/  ──▶ │ nginx:80           opensandbox-server:8080
    │  sandboxes/{id}/         │   └──proxy_pass──▶     (opensandbox ns)
    │  proxy/{port}            │                          │
    │                          │                          │ BatchSandbox CRD
    │                          │                          ▼
    │                          │                   Operator (opensandbox-system ns)
    │                          │                          │
    │                          │                          ▼
    │                          │                   Sandbox Pods (opensandbox ns)
    │                          │                   code-interpreter:v1.0.1
```

## 镜像清单

| 镜像 | 来源 | 用途 |
|------|------|------|
| `opensandbox-controller:local` | 源码构建 | K8s Operator |
| `opensandbox-task-executor:local` | 源码构建 | Sandbox 任务执行器 |
| `sandbox-registry.../server:latest` | 公开 Registry | OpenSandbox API Server |
| `sandbox-registry.../execd:v1.0.6` | 公开 Registry | Sandbox execd 守护进程 |
| `sandbox-registry.../task-executor:latest` | 公开 Registry | Pool 内 task-executor |
| `sandbox-registry.../code-interpreter:v1.0.1` | 公开 Registry | 代码解释器沙箱镜像 |
