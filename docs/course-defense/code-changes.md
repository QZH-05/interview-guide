# 代码修改清单

> **状态：必修 1–3 与“加分 3”已在本仓库应用**，并通过以下验证：
> `pnpm exec tsc --noEmit`、`vite build`、`gradlew :app:compileJava`。
> 加分 1（/api/net/info）与加分 2（限流可信代理）仍按需手动添加。
>
> 目的：让“真实客户端（PC-B）通过 eNSP 访问”这件事成立。
> 前 3 项是**必修**，不改会导致语音面试连不上；后 3 项是**加分**，用于答辩讲“网络 + 代码”。

---

## 必修 1：nginx 代理 `/ws/` 并关闭 SSE 缓冲

**文件**：`frontend/nginx.conf`

**问题**：当前只代理了 `/api/`，WebSocket 路径 `/ws/` 没有代理；
且未关闭 `proxy_buffering`，SSE 会被 nginx 缓冲，前端看不到逐字输出。

**改法**：把 `location /api/` 替换为下面内容，并新增 `location /ws/`。

```nginx
    location /api/ {
        proxy_pass http://app:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # SSE 必须关闭缓冲，否则字幕不会逐字出现
        proxy_buffering off;
        proxy_cache off;
        # 大模型请求可能很慢
        proxy_read_timeout 300s;
        proxy_connect_timeout 300s;
        proxy_send_timeout 300s;
    }

    location /ws/ {
        proxy_pass http://app:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        # 语音面试是长连接，不能 60s 就断
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
```

**验证**：

```bash
docker compose build frontend
docker compose up -d frontend
# 浏览器 DevTools → Network → WS，状态应为 101
```

---

## 必修 2：前端 WebSocket 地址按当前页面生成

**文件**：`frontend/src/pages/VoiceInterviewPage.tsx`

**问题**：后端返回 `ws://localhost:8080/...`，在 PC-B 上会连到 PC-B 自己；前端兜底也是
`ws://localhost:8080`。必须改成基于 `window.location`。

**第 1 步**：在文件顶部（import 之后）加一个工具函数：

```ts
function resolveWsUrl(raw: string | undefined, sessionId: number): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const fallback = `${protocol}//${window.location.host}/ws/voice-interview/${sessionId}`;
  if (!raw) return fallback;

  // 兼容后端旧值：ws://localhost:8080/ws/...
  if (raw.startsWith('ws://') || raw.startsWith('wss://')) {
    try {
      const url = new URL(raw);
      if (url.hostname === 'localhost' || url.hostname === '127.0.0.1') {
        return `${protocol}//${window.location.host}${url.pathname}`;
      }
      return raw;
    } catch {
      return fallback;
    }
  }

  // 后端返回相对路径：/ws/voice-interview/1
  return `${protocol}//${window.location.host}${raw.startsWith('/') ? '' : '/'}${raw}`;
}
```

**第 2 步**：把两处旧代码

```ts
const wsUrl = session.webSocketUrl || `ws://localhost:8080/ws/voice-interview/${session.sessionId}`;
```

替换为

```ts
const wsUrl = resolveWsUrl(session.webSocketUrl, session.sessionId);
```

（分别在 `handlePhaseConfig` 和 `handleResumeSession` 中各一处，共 2 处）

**验证**：PC-B 打开 `http://interview.local`，进入语音面试，DevTools 里 WS 地址应为
`ws://interview.local/ws/voice-interview/xx`。

---

## 必修 3：CORS / WebSocket 来源白名单

**文件**：`.env`（本地）与 `.env.example`（模板）

**问题**：`WebSocketConfig` 用 `CorsProperties.allowedOrigins` 做握手校验，
浏览器从 `http://interview.local` 发起时，Origin 不在白名单会直接 **403**。

**改法**：

```dotenv
CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:5174,http://localhost,http://interview.local,http://10.20.0.10
```

同时更新 `.env.example` 中的示例值（不要写真实密钥）。

**验证**：

```bat
:: PC-B
curl -i http://interview.local/api/net/info
```

---

## 必修项汇总验证

| 检查 | 命令 / 操作 | 期望 |
|---|---|---|
| HTTP 通 | PC-B 打开 `http://interview.local` | 页面正常 |
| SSE 通 | 知识库问答 | 字幕逐字出现 |
| WS 通 | 语音面试 | Network 里 101，能听到 AI 语音 |
| 代理头 | `curl -H "X-Forwarded-For: 9.9.9.9" http://interview.local/api/net/info` | 返回 `xForwardedFor` |

---

# 加分项（建议做，答辩时是亮点）

## 加分 1：新增 `/api/net/info`，把"网络身份"显示出来

**作用**：从不同 VLAN 访问时，页面显示的客户端 IP、来源网段会变化，
直观证明流量确实穿过了接入层、核心、NAT 和反向代理。

### 1.1 新建 DTO

`app/src/main/java/interview/guide/modules/system/dto/NetworkInfoResponse.java`

```java
package interview.guide.modules.system.dto;

/**
 * 当前请求的网络身份信息，用于演示代理链与网段接入。
 */
public record NetworkInfoResponse(
        String remoteAddr,
        String clientIp,
        String xForwardedFor,
        String xRealIp,
        String scheme,
        int serverPort,
        String host,
        String origin
) {
}
```

### 1.2 新建 Service

`app/src/main/java/interview/guide/modules/system/service/NetworkInfoService.java`

```java
package interview.guide.modules.system.service;

import interview.guide.modules.system.dto.NetworkInfoResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

@Service
public class NetworkInfoService {

    public NetworkInfoResponse inspect(HttpServletRequest request) {
        return new NetworkInfoResponse(
                request.getRemoteAddr(),
                resolveClientIp(request),
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getScheme(),
                request.getServerPort(),
                request.getHeader("Host"),
                request.getHeader("Origin")
        );
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return realIp != null && !realIp.isBlank() ? realIp : request.getRemoteAddr();
    }
}
```

### 1.3 新建 Controller

`app/src/main/java/interview/guide/modules/system/controller/NetworkInfoController.java`

```java
package interview.guide.modules.system.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.system.dto.NetworkInfoResponse;
import interview.guide.modules.system.service.NetworkInfoService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class NetworkInfoController {

    private final NetworkInfoService networkInfoService;

    @GetMapping("/api/net/info")
    public Result<NetworkInfoResponse> info(HttpServletRequest request) {
        return Result.success(networkInfoService.inspect(request));
    }
}
```

**答辩用法**：

- PC-B（192.168.20.x）访问 → 观察 `remoteAddr` / `xForwardedFor`
- PC-A 本机访问 → 显示 10.20.0.10
- 演示 `curl -H "X-Forwarded-For: 1.1.1.1"` → 显示伪造值，引出加分 2

> **Docker 注意**：Windows 上 Docker Desktop 的端口发布经过用户态代理，容器
> 看到的源地址通常是 Docker 网关（172.x），不是 192.168.20.x。
> 这本身就是"多层 NAT"的真实演示，答辩时直接讲：
> "PC-B → eNSP NAT → Docker NAT → nginx → 应用，共三层地址转换"。
>
> 如果一定要让应用看到真实客户端 IP：在主机上用原生 nginx 监听 80，
> `proxy_pass http://127.0.0.1:8080`，再访问；或把 `X-Forwarded-For`
> 作为唯一可信来源（加分 2 的配置正是为此准备的）。

---

## 加分 2：修复限流 IP 可伪造问题（真实缺陷）

**文件**：`app/src/main/java/interview/guide/common/aspect/RateLimitAspect.java`

**问题**：当前代码**无条件信任** `X-Forwarded-For`，任何人加一个请求头就能绕过 IP 限流。

**原理**：只有"直连对端是可信代理"时，才允许使用代理头；否则用 `getRemoteAddr()`。

### 2.1 新增配置类

`app/src/main/java/interview/guide/common/config/NetworkProperties.java`

```java
package interview.guide.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app.network")
public class NetworkProperties {

    /** 可信代理网段（CIDR），只有来自这些地址的请求才解析 X-Forwarded-For */
    private List<String> trustedProxies = List.of("127.0.0.1", "::1", "172.16.0.0/12");
}
```

### 2.2 配置

`app/src/main/resources/application.yml` 的 `app:` 下增加：

```yaml
  network:
    trusted-proxies: ${APP_NETWORK_TRUSTED_PROXIES:127.0.0.1,::1,172.16.0.0/12}
```

> Docker 部署时 nginx 的地址是 `172.x`，所以信任 `172.16.0.0/12`；
> **不要**信任 `10.0.0.0/8`，否则学生网段也能伪造。

### 2.3 修改 Aspect

构造函数注入 `NetworkProperties`，并把 `getClientIp()` 替换为：

```java
    private String getClientIp() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }

        HttpServletRequest request = attributes.getRequest();
        String remoteAddr = request.getRemoteAddr();
        String ip = null;

        if (isTrustedProxy(remoteAddr)) {
            ip = firstNonBlank(
                    request.getHeader("X-Forwarded-For"),
                    request.getHeader("X-Real-IP"),
                    request.getHeader("Proxy-Client-IP"));
        }

        if (ip == null || ip.isBlank()) {
            ip = remoteAddr;
        }
        if (ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null) {
            return false;
        }
        return networkProperties.getTrustedProxies().stream()
                .anyMatch(cidr -> matchesCidr(remoteAddr, cidr));
    }

    private boolean matchesCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            InetAddress address = InetAddress.getByName(ip);
            InetAddress network = InetAddress.getByName(parts[0]);
            if (!(address instanceof Inet4Address) || !(network instanceof Inet4Address)) {
                return address.equals(network);
            }
            int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : 32;
            int mask = prefix == 0 ? 0 : 0xFFFFFFFF << (32 - prefix);
            int a = ByteBuffer.wrap(address.getAddress()).getInt();
            int n = ByteBuffer.wrap(network.getAddress()).getInt();
            return (a & mask) == (n & mask);
        } catch (Exception e) {
            return false;
        }
    }
```

需要新增 import：

```java
import interview.guide.common.config.NetworkProperties;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
```

**答辩演示**：

```bat
:: 修改前：伪造 XFF 可以绕过限流
for /L %i in (1,1,20) do curl -s -o NUL -H "X-Forwarded-For: 1.1.1.%i" http://interview.local/api/knowledgebase/query

:: 修改后：伪造无效，仍然按真实 IP 限流
```

---

## 加分 3：后端返回相对 WebSocket 路径（可选）

**文件**：`app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewService.java`

```java
.webSocketUrl(String.format("/ws/voice-interview/%d", session.getId()))
```

配合必修 2 的 `resolveWsUrl`，前端会自动拼成 `ws://当前域名/ws/...`。
这样即使以后加 HTTPS，也能自动变成 `wss://`。

---

## 建议的提交顺序

1. 先做必修 1–3，跑通端到端
2. 再做加分 1，录一段"不同网段 IP 变化"的演示
3. 最后做加分 2，录一段"伪造 XFF 绕过限流 → 修复后失效"的对比

每一步单独 commit，答辩时可以展示 Git 记录，体现"发现问题 → 定位 → 修复"的工程过程。
