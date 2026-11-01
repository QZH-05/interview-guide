# 计算机网络课程设计答辩方案
## 基于 eNSP 的 AI 面试平台网络规划与部署

> 适用项目：InterviewGuide（Spring Boot 4 + React 18 + Spring AI）
> 课程：计算机网络技术
> 目标：同时满足「网络相关 + 软件工程有代码 + 使用大模型 + 有原理有步骤 + 完整代码」

---

## 0. 一句话方案

**eNSP 负责"网络世界"，真实主机负责"应用世界"**：
在 eNSP 中搭建「出口路由 + 三层核心 + 接入层 + DMZ」的校园网，把 InterviewGuide 用 Docker Compose 部署在 DMZ 的真实主机上（10.20.0.10），客户端通过 VLAN20 接入，完整演示
**DHCP → DNS → HTTP → WebSocket → SSE → NAT** 的端到端链路，并在每条链路上抓包。

这套方案能同时讲清：VLAN/Trunk、静态路由 + OSPF、DHCP、NAT、ACL、QoS、DNS、HTTP/WebSocket/SSE、TCP 三次握手与抓包分析。

---

## 1. 选题定位

**建议题目**：《校园网环境下 AI 面试平台的网络规划、部署与协议分析》

| 答辩要求 | 本方案如何满足 | 证据 |
|---|---|---|
| 与计算机网络技术相关 | eNSP 完成 VLAN/路由/NAT/ACL/DHCP/DNS/QoS 规划与配置；对 HTTP/WS/SSE/TCP 做抓包分析 | `.topo` + `.cfg` + `.pcapng` |
| 结合软件工程专业（有代码） | 完整前后端源码，Java 21 虚拟线程、Redis Stream、Lua 限流、WebSocket 音频流 | 仓库源码 |
| 大模型使用 | Spring AI 多 Provider + pgvector RAG + Qwen3 ASR/TTS + 结构化输出 | `app.ai.providers.*` |
| 不能过于简单 | 网络侧 6 类设备 + 应用侧 5 个容器 + 协议级抓包 + 性能对比 | 见第 5 节 |
| 完整代码 | 源码 + compose + 配置脚本 + 抓包文件 + 文档 | 见第 10 节 |

**答辩主线**（一句话讲给老师）：
> "我的课题是一个 AI 面试平台，它有实时语音和流式字幕，对网络时延、带宽和安全都有硬性要求。所以我先做网络规划，在 eNSP 里把校园网和 DMZ 建起来，再把应用部署进去，最后用抓包证明业务流量确实走过了我设计的每一条链路。"

---

## 2. 关键决策：为什么不用 USG6000V 防火墙

eNSP 的 **USG6000V / AC / AP 依赖 VirtualBox**，而 VirtualBox 5.2.x 与 Docker Desktop 的 WSL2/Hyper-V 冲突，在同一台 Windows 上极易出现"防火墙起不来"或"Docker 起不来"。

因此主方案：

- **用 AR2220 路由器的 ACL 实现包过滤防火墙功能**（`traffic-filter inbound acl 3000`）
- 课程知识点（ACL、NAT、DMZ 隔离、最小权限）一个不少
- 答辩时表述为"出口安全网关（包过滤防火墙）"，不要谎称是状态防火墙

**如果老师明确要求状态防火墙**，升级方案见 `README-optional-usg.md`（需要单独一台 Win10 或关闭 Hyper-V）。

---

## 3. 拓扑结构

```
                         ┌────────────────┐
                         │ ISP-PUB        │ 202.100.1.2/30
                         └───────┬────────┘
                                 │ 202.100.1.0/30
                         ┌───────┴────────┐
                         │ R1   AR2220    │ NAT + ACL（安全网关）
                         └───┬────────┬───┘
                  10.0.1/24  │        │  10.20.0/24
                  ┌──────────┴───┐  ┌─┴─────────────────┐
                  │ CORE  S5700  │  │ DMZ-SW  S3700     │
                  │ 三层网关/DHCP │  │                   │
                  └──┬────────┬──┘  ├─ DNS  10.20.0.53
                     │        │     ├─ Cloud2 → 主机 10.20.0.10
                  ┌──┴───┐ ┌──┴───┐ └───────────────────┘
                  │ ACC1 │ │ ACC2 │
                  └──┬───┘ └──┬───┘
              VLAN10 │        │ VLAN20
            PC-TEACHER       Cloud1 → 主机网卡 → PC-B（真实客户端）
```

**设备清单**

| 设备 | 型号 | 作用 | 关键配置 |
|---|---|---|---|
| R1 | AR2220 | 出口路由、NAT、ACL、默认路由 | `R1.cfg` |
| CORE | S5700 | 三层核心、VLAN 网关、DHCP、OSPF | `CORE.cfg` |
| ACC1 | S3700 | 办公接入 | `ACC1.cfg` |
| ACC2 | S3700 | 学生 / 真实客户端接入 | `ACC2.cfg` |
| DMZ-SW | S3700 | 服务器区二层 | `DMZ-SW.cfg` |
| ISP-PUB | Server | 模拟公网服务 | 见 `ensp/README.md` |
| DNS-Server | Server | 内网 DNS | 见 `ensp/README.md` |
| PC-TEACHER | PC | 办公终端 | DHCP |
| Cloud | Cloud | 桥接真实主机 | 见 `ensp/README.md` |

> 具体端口与连线见 `ensp/README.md`。

---

## 4. 地址与 VLAN 规划

| VLAN | 网段 | 网关（CORE） | 用途 | DHCP |
|---|---|---|---|---|
| 10 | 192.168.10.0/24 | 192.168.10.1 | 办公 / 教师 | 全局地址池 |
| 20 | 192.168.20.0/24 | 192.168.20.1 | 学生 / 真实客户端 | 全局地址池 |
| 30 | 192.168.30.0/24 | 192.168.30.1 | 无线（选做） | 全局地址池 |
| 99 | 192.168.99.0/24 | 192.168.99.1 | 设备管理 | 静态 |
| 100 | 10.0.1.0/24 | — | CORE ↔ R1 互联 | 静态 |
| — | 10.20.0.0/24 | 10.20.0.1（R1） | DMZ 服务器区 | 静态 |
| — | 202.100.1.0/30 | — | 模拟公网 | 静态 |

**关键地址**

| 主机 | 地址 | 说明 |
|---|---|---|
| 应用服务器（真实主机环回） | 10.20.0.10/24 | nginx:80、app:8080、pg:5432、redis:6379、minio:9000/9001 |
| 内网 DNS | 10.20.0.53/24 | `interview.local → 10.20.0.10` |
| 公网模拟服务 | 202.100.1.2/30 | 用于演示 NAT |
| 设备管理 | 192.168.99.11/12 | ACC1 / ACC2 |

**域名**：`interview.local`（答辩时用域名访问，比敲 IP 更能体现 DNS）

---

## 5. 课程知识点落点

| 课程知识点 | 方案中的落点 | 验证方式 |
|---|---|---|
| OSI / TCP-IP 分层 | 从 VLAN20 到 DMZ 的完整封装与解封装 | eNSP 链路抓包看 ARP/ICMP/TCP |
| IP 编址与子网划分 | /24、/30 互联段、VLAN 与网段对应 | `display ip routing-table` |
| ARP | 跨网段时逐跳 ARP | Wireshark 过滤 `arp` |
| VLAN / Trunk | 接入 VLAN10/20，Trunk 放行 | `display vlan`、`display port vlan` |
| STP | 默认 MSTP，防环 | `display stp brief` |
| 静态路由 | R1 默认路由指向 ISP | `display ip routing-table` |
| OSPF | R1 ↔ CORE 动态路由，R1 下发默认路由 | `display ospf peer brief` |
| DHCP | CORE 全局地址池下发 IP/DNS | `display ip pool` |
| NAT | R1 `nat outbound` 访问模拟公网 | `display nat session all` |
| ACL | DMZ 只开放 80/53/ICMP，封锁 5432/6379/9001 | `Test-NetConnection` 被拒 + 抓包 |
| DNS | 内网 DNS 解析 `interview.local` | `ping interview.local` |
| HTTP / HTTPS | 前端静态资源 + `/api` 反向代理 | 抓包看请求行/响应头 |
| WebSocket | 语音面试 `/ws/voice-interview/{id}` 升级握手 | 抓包看 `101 Switching Protocols` |
| SSE | RAG 流式回答 `text/event-stream` | 抓包看 `Transfer-Encoding: chunked` |
| TCP | 三次握手、四次挥手、长连接 | Wireshark 过滤 `tcp.flags` |
| 反向代理 | nginx `proxy_pass` + `X-Forwarded-For` | 对比直连 8080 与 80 |
| QoS | 学生网段限速 + 语音 DSCP EF（选做） | `display traffic policy statistics` |
| 网络管理 | 设备管理 VLAN + Telnet/SSH（选做） | 远程登录 |

---

## 6. 实施步骤

> 总工期约 8–10 天，按顺序做，每步都有验收命令，**不要跳步**。

### Step 0：环境准备（0.5 天）

| 软件 | 版本 | 注意 |
|---|---|---|
| eNSP | 1.3.00.100 | 不要用最新 WinPcap，装 Npcap 兼容模式 |
| VirtualBox | 5.2.44 | 本方案不用 USG，可不装 |
| Wireshark | 4.x | 与 Npcap 一起装 |
| Docker Desktop | 最新 | WSL2 后端 |
| JDK 21 / Node 20 | — | 只在本地调试时需要 |

验收：eNSP 能启动，拖入 AR2220 和 S5700 能正常开机。

### Step 1：搭拓扑（0.5 天）

按 `ensp/README.md` 的连线表把 9 台设备连好，先**不要配任何东西**，全部启动。

验收：所有设备 `ping` 直连邻居前先看接口 `display interface brief` 为 UP。

### Step 2：二层（0.5 天）

粘贴 `ACC1.cfg`、`ACC2.cfg`、`DMZ-SW.cfg` 中的二层部分：VLAN、Access、Trunk。

验收：
```
[ACC1] display vlan
[ACC1] display port vlan
[CORE] display mac-address
```
能学到 PC 的 MAC，Trunk 上能看到多个 VLAN 的 TAG。

### Step 3：三层与 DHCP（1 天）

粘贴 `CORE.cfg`：Vlanif、地址池、`dhcp select global`。

验收：
```
[CORE] display ip interface brief
[CORE] display ip pool
[PC-TEACHER] ipconfig          # 拿到 192.168.10.x，DNS = 10.20.0.53
```

### Step 4：路由（1 天）

粘贴 `R1.cfg` 的路由部分，启用 OSPF；R1 用静态默认路由指向 ISP。

验收：
```
[R1]   display ospf peer brief        # FULL
[CORE] display ip routing-table       # 学到 0.0.0.0/0 和 10.20.0.0/24
[R1]   display ip routing-table       # 学到 192.168.10/20/30
```

### Step 5：NAT / ACL / DNS（1 天）

粘贴 `R1.cfg` 的 NAT 与 ACL；在 eNSP 的 Server 上配 DNS。

验收：
```
[PC-TEACHER] ping 202.100.1.2          # 通
[R1]         display nat session all   # 看到 192.168.10.x → 202.100.1.1 的转换
[R1]         ping interview.local      # 解析到 10.20.0.10
```
PC 上验证端口（PowerShell）：
```powershell
Test-NetConnection 10.20.0.10 -Port 5432   # 期望失败（ACL 生效）
Test-NetConnection 10.20.0.10 -Port 80     # 期望成功
```

### Step 6：主机与 Cloud（1 天）

1. 安装 **Microsoft KM-TEST 环回适配器**，命名 `eNSP-Server`，IP `10.20.0.10/24`，**不配网关**。
2. eNSP 添加 Cloud，两个端口：
   - 端口1 绑定**有线网卡** → 接 ACC2 `E0/0/1`（PC-B 用网线接到这块网卡）
   - 端口2 绑定 `eNSP-Server` → 接 DMZ-SW `E0/0/1`
3. 有线网卡**取消 IPv4**（避免和 PC-B 抢地址），主机上网走 Wi-Fi。
4. **添加回程路由（必须，否则 PC-B 能连进来但收不到响应）**：
```powershell
# 管理员权限执行；-p 表示永久生效
route -p add 192.168.10.0 mask 255.255.255.0 10.20.0.1
route -p add 192.168.20.0 mask 255.255.255.0 10.20.0.1
route -p add 192.168.30.0 mask 255.255.255.0 10.20.0.1
```
> 原理：PC-B 的地址不属于 10.20.0.0/24，主机回复时必须把包交回
> 10.20.0.1（R1 的 DMZ 口），否则会走 Wi-Fi 默认路由而被丢弃。
> 这些明细路由不影响主机上网，默认路由仍在 Wi-Fi。
5. 放行防火墙：
```powershell
New-NetFirewallRule -DisplayName "eNSP-Demo" -Direction Inbound -Protocol TCP `
  -LocalPort 80,8080,9000 -Action Allow -Profile Any
```

验收：
```powershell
curl http://10.20.0.10          # 主机本机可访问
# PC-B: ipconfig 拿到 192.168.20.x
# PC-B: ping 10.20.0.10 通
```

> 如果主机访问 `10.20.0.10:80` 不通，加端口转发（Docker Desktop 只监听 127.0.0.1 时的兜底）：
> ```powershell
> netsh interface portproxy add v4tov4 listenaddress=10.20.0.10 listenport=80 `
>   connectaddress=127.0.0.1 connectport=80
> ```

### Step 7：应用部署（1 天）

```bash
cp .env.example .env      # 填 AI_BAILIAN_API_KEY
# 修改 CORS 白名单（见 code-changes.md 必修 3）
docker compose up -d --build
docker compose ps
```

`code-changes.md` 里的 **3 项必修修改**已在本仓库应用（状态见该文档顶部）。如果换了分支或重新拉代码，请确认这三处仍然存在：

1. nginx 增加 `/ws/` 反向代理（含 `Upgrade` 头）
2. 前端 WebSocket 地址改为 `window.location`
3. CORS / WebSocket 允许 `http://interview.local`

验收：PC-B 浏览器打开 `http://interview.local`，能上传简历、开始文字面试（本项目无登录页）。

### Step 8：联调与抓包（1 天）

见 `demo.md`，把每条链路的抓包都保存成 `.pcapng`。

### Step 9：QoS / 冗余（选做，0.5 天）

- 学生网段限速：`ensp/QOS-optional.cfg` 中的 `LIMIT_STUDENT`
- 语音优先：DSCP EF 标记
- 双核心 VRRP + OSPF：见 `README-optional-ha.md`（如果老师追问"单点故障怎么办"）

---

## 7. 验收清单

- [ ] 所有设备接口 UP，VLAN/Trunk 正确
- [ ] PC 通过 DHCP 拿到地址，DNS 指向 10.20.0.53
- [ ] 跨网段 `ping` 通，`tracert` 路径为 `192.168.20.1 → 10.0.1.1 → 10.20.0.10`
- [ ] OSPF 邻居 FULL，路由表有默认路由
- [ ] `display nat session all` 有会话
- [ ] `Test-NetConnection 10.20.0.10 -Port 5432` 失败，`-Port 80` 成功
- [ ] `ping interview.local` 能解析
- [ ] PC-B 浏览器能打开平台并完成一次文字面试
- [ ] PC-B 能完成一次语音面试（WebSocket 通）
- [ ] 抓包文件包含：ARP、ICMP、TCP 三次握手、HTTP GET、WebSocket 101、SSE chunked
- [ ] 报告 / PPT / 演示视频齐全

---

## 8. 时间表

| 天 | 任务 | 产出 |
|---|---|---|
| D1 | 环境 + 拓扑 | 能开机的 `.topo` |
| D2 | 二层 + 三层 + DHCP | 内网互通 |
| D3 | 路由 + NAT + ACL + DNS | 全链路通 |
| D4 | 主机 + Cloud + 应用部署 | 浏览器可访问 |
| D5 | 代码修复 + 端到端联调 | 语音面试可用 |
| D6 | 抓包 + 截图 + QoS | `.pcapng` + 素材 |
| D7 | 写报告 | 课程设计报告 |
| D8 | 做 PPT + 排练 | 10 分钟讲稿 |
| D9 | 备份 + 预演 | 录屏、拓扑备份 |

---

## 9. 风险与降级

| 风险 | 概率 | 应对 |
|---|---|---|
| Docker Desktop 与 eNSP 抢网卡/冲突 | 中 | 本方案不含 USG，不用 VirtualBox；应用与 eNSP 可分开跑 |
| 主机 `10.20.0.10:80` 不通 | 中 | `netsh portproxy` 兜底；或改为 `java -jar` 原生运行 |
| 现场没有外网，大模型调不通 | 高 | 提前录屏；或切到 LM Studio 本地 Provider（`app.ai.providers.lmstudio`） |
| PC-B 拿不到 DHCP | 中 | 检查 Cloud 端口绑定、网线、ACC2 端口 VLAN；改静态 IP 192.168.20.100 |
| eNSP 崩溃 | 中 | 保存 `.topo`；关键截图提前拍好；准备录屏 |
| 老师要求状态防火墙 | 低 | 升级 USG6000V（单独 Win10），见 `README-optional-usg.md` |
| 老师要求纯仿真 | 低 | 明确说明"应用服务器是数据中心外部系统"，网络部分仍全部在 eNSP 内 |

---

## 10. 交付物

```
答辩材料/
├── 01-源码/                 # 本仓库
├── 02-拓扑/
│   ├── interview-guide.topo
│   └── ensp/*.cfg           # 各设备配置
├── 03-抓包/
│   ├── arp-icmp.pcapng
│   ├── http.pcapng
│   ├── websocket-101.pcapng
│   ├── sse-chunked.pcapng
│   └── nat-acl.pcapng
├── 04-报告/课程设计报告.pdf
├── 05-PPT/答辩.pptx
└── 06-演示/演示视频.mp4
```

---

## 11. 报告与 PPT 结构

**报告章节**

1. 需求分析（业务场景 + 网络需求：时延、带宽、安全、可用性）
2. 网络规划设计（拓扑、VLAN、地址、路由、NAT、ACL、QoS）
3. 关键技术原理（TCP/IP、HTTP/WS/SSE、NAT、ACL、反向代理）
4. 系统设计与实现（架构、模块、关键代码）
5. 部署与测试（连通性、抓包、性能对比）
6. 总结与改进

**PPT 页序（12 页）**

1. 封面
2. 选题背景与三个要求
3. 系统架构（应用侧）
4. 网络需求分析
5. 拓扑设计
6. VLAN 与地址规划
7. 路由与 NAT
8. 安全设计（ACL / DMZ）
9. 部署实现（Cloud + Docker）
10. 抓包分析（3 张图）
11. 测试结果与 QoS
12. 总结与展望

---

## 12. 配套文件

| 文件 | 内容 |
|---|---|
| `ensp/README.md` | 设备连线、Cloud/Server/DNS 配置、验证命令 |
| `ensp/R1.cfg` | 出口路由、NAT、ACL、OSPF |
| `ensp/CORE.cfg` | VLAN、网关、DHCP、OSPF、QoS |
| `ensp/ACC1.cfg` / `ACC2.cfg` | 接入层 |
| `ensp/DMZ-SW.cfg` | 服务器区 |
| `ensp/QOS-optional.cfg` | 语音优先 + 学生限速 |
| `demo.md` | 10 分钟答辩演示脚本 |
| `code-changes.md` | 必须完成的 3 项代码修复 + 3 项加分项 |
| `README-optional-usg.md` | 升级为 USG6000V 状态防火墙 |
| `README-optional-ha.md` | 双核心 VRRP + OSPF 高可用 |
| `ppt/答辩.pptx` | 12 页答辩 PPT（16:9，含演讲者备注） |
| `ppt/讲稿.md` | 逐页口播稿 |
| `ppt/outline.md` | 大纲、时间分配、素材替换清单 |
| `ppt/build_ppt.py` | PPT 生成脚本（可改配色/内容后重新生成） |
