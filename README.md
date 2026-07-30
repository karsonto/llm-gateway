# llm-gateway

基于 **Netty + Java 8** 的 vLLM / OpenAI 兼容反向代理网关，支持 API Key 鉴权、用量统计、SQLite 持久化与管理台。

## 功能

- 路径白名单转发（`/v1/chat/completions`、`/v1/completions` 等）
- API Key 鉴权（`Authorization: Bearer` 或 `X-API-Key`）
- 按 apiKey / 日期 / model 聚合用量统计
- SQLite 持久化 Key 与用量
- Web 管理台（`/admin/`）：Key 管理、用量图表
- Docker 镜像，支持挂载外部配置文件

## 快速开始

### 本地运行

```bash
# 构建（含已嵌入的 admin 静态资源）
mvn package -DskipTests

# 启动
java -jar target/llm-gateway-1.0-SNAPSHOT.jar
```

默认监听 `8088`，上游 vLLM 地址见 `src/main/resources/gateway.properties`。

### 重新构建管理台前端

修改 `admin-web/` 后执行：

```bash
./scripts/build-admin-web.sh
mvn package -DskipTests
```

## 配置

默认配置在 jar 内 `classpath:gateway.properties`。可通过**外部文件覆盖**同名项，无需改 jar。

### 外部配置文件

优先级（高 → 低）：

1. JVM 参数 `-Dgateway.config=/path/to/gateway.properties`
2. 环境变量 `GATEWAY_CONFIG=/path/to/gateway.properties`
3. classpath 内 `gateway.properties`（默认值）

外部文件存在时加载并覆盖默认值；路径已设置但文件不存在时，回退到 classpath 并打印 warn 日志。

```bash
# 环境变量
export GATEWAY_CONFIG=/etc/llm-gateway/gateway.properties
java -jar target/llm-gateway-1.0-SNAPSHOT.jar

# 或 JVM 参数（优先级更高）
java -Dgateway.config=/etc/llm-gateway/gateway.properties -jar target/llm-gateway-1.0-SNAPSHOT.jar
```

参考示例：[docker/gateway.properties.example](docker/gateway.properties.example)

### 主要配置项

| 配置项 | 说明 | 示例 |
|--------|------|------|
| `gateway.port` | 网关监听端口 | `8088` |
| `vllm.host` / `vllm.port` | 上游 vLLM 地址 | `127.0.0.1` / `8000` |
| `gateway.path.whitelist` | 允许转发的路径（逗号分隔） | `/v1/chat/completions,...` |
| `gateway.sqlite.path` | SQLite 数据库路径（启用后 Key/用量持久化） | `./data/gateway.db` |
| `gateway.admin.username` | 管理台用户名 | `admin` |
| `gateway.admin.password` | 管理台密码 | `admin123` |
| `gateway.api.keys` | 内存模式 Key（仅未启用 SQLite 时生效） | `sk-xxx:alice` |

启用 SQLite 后，`gateway.api.keys` 被忽略，Key 通过管理台或数据库维护。

## Docker

### 本地构建镜像

```bash
docker build --platform linux/amd64 -t llm-gateway .
```

### 运行

```bash
# 1. 准备配置与数据目录
mkdir -p config data
cp docker/gateway.properties.example config/gateway.properties
# 编辑 config/gateway.properties（vllm.host、admin 密码等）

# 2. 启动（本地构建的镜像）
docker run -d \
  --name llm-gateway \
  -p 8088:8088 \
  -v "$(pwd)/config:/app/config" \
  -v "$(pwd)/data:/app/data" \
  llm-gateway

# 或使用 Docker Hub 镜像
# docker run -d --name llm-gateway -p 8088:8088 \
#   -v "$(pwd)/config:/app/config" -v "$(pwd)/data:/app/data" \
#   <your-dockerhub-username>/llm-gateway:latest
```

镜像内默认 `GATEWAY_CONFIG=/app/config/gateway.properties`。未挂载配置文件时，使用 jar 内默认配置启动。

自定义 JVM 参数：

```bash
docker run -d \
  -p 8088:8088 \
  -e JAVA_OPTS="-Xmx512m" \
  -v "$(pwd)/config:/app/config" \
  -v "$(pwd)/data:/app/data" \
  llm-gateway
```

## 管理台

访问 `http://<host>:<port>/admin/`，使用 `gateway.admin.username` / `gateway.admin.password` 登录。

管理台需要 SQLite（`gateway.sqlite.path`），用于 Key CRUD 与用量图表。

## API

### 鉴权

请求头任选其一：

```
Authorization: Bearer <api-key>
X-API-Key: <api-key>
```

未配置任何 Key（内存模式）或 SQLite `api_keys` 表为空时，网关处于开放模式，不强制鉴权。

### 用量查询

```
GET /v1/usage          # 需 API Key，返回当前 Key 用量
GET /v1/admin/usage    # 无鉴权，返回全部用量（可按部署环境限制访问）
```

## 项目结构

```
src/main/java/org/icbca/gateway/   # 网关核心
admin-web/                           # 管理台前端（Vite + React）
src/main/resources/static/admin/     # 前端构建产物（嵌入 jar）
docker/                              # Docker 配置示例
scripts/build-admin-web.sh           # 前端构建脚本
```
