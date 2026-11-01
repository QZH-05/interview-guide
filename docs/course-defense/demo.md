# 答辩演示脚本（10 分钟）

> 目标：让老师看到"网络是设计出来的、应用是跑在网络上的、协议是抓得出来的"。
> 全程只讲三件事：**我怎么规划 → 怎么验证 → 报文长什么样**。

---

## 0. 演示环境

| 角色 | 设备 | 作用 |
|---|---|---|
| PC-A | 笔记本 | 跑 eNSP + Docker Compose（应用服务器） |
| PC-B | 另一台电脑 | 真实客户端，通过 VLAN20 接入 |
| 投影 | — | 建议 eNSP 与浏览器左右分屏 |

**建议提前打开的窗口**

1. eNSP 拓扑（设备全部绿色）
2. PC-B 的浏览器：`http://interview.local`
3. PC-B 的终端（`cmd`）
4. PC-A 的 Wireshark（预先过滤好）
5. 备用：演示录屏、关键截图

---

## 1. 演示前 30 分钟检查清单

- [ ] 所有设备开机，接口 UP，无红色告警
- [ ] PC-B 能 `ping 192.168.20.1`、`ping 10.20.0.10`
- [ ] PC-B 浏览器能打开 `http://interview.local`
- [ ] 能上传一份简历并看到分析结果
- [ ] 能进入语音面试并听到 AI 第一句话（麦克风权限已授权）
- [ ] `Test-NetConnection 10.20.0.10 -Port 5432` 失败（ACL 生效）
- [ ] Wireshark 能抓到包
- [ ] 关闭 PC-B 的 Wi-Fi（避免默认路由干扰）
- [ ] 关闭系统通知、微信、录屏提示
- [ ] 录屏已开始（OBS / Windows 录屏）

---

## 2. 时间轴

### 0:00 – 1:00 开场（1 分钟）

> "我的课题是 AI 面试平台。它有三个网络特征：实时语音走 WebSocket、
> 大模型回答走 SSE 流式、简历和知识库要上传下载。
> 这些业务对时延、带宽、安全都有要求，所以我先做网络规划，
> 在 eNSP 里把校园网和数据中心建起来，再把应用部署进去。"

**屏幕**：PPT 第 1–3 页（背景 + 架构）。

---

### 1:00 – 2:30 拓扑讲解（1.5 分钟）

**屏幕**：eNSP 拓扑。

按"从外到内"讲：

1. **出口**：R1 做 NAT 和 ACL，模拟公网出口
2. **核心**：CORE 是三层网关，跑 OSPF 和 DHCP
3. **接入**：ACC1 办公 VLAN10、ACC2 学生 VLAN20
4. **DMZ**：服务器区 10.20.0.10，只开放 80 和 DNS
5. **Cloud**：把真实主机桥接进仿真网络

> "这里没有用 USG 防火墙，是因为它依赖 VirtualBox，会和 Docker 的 WSL2
> 冲突。所以我用路由器的 ACL 实现包过滤防火墙，功能上覆盖了课程要求。"

> 如果老师追问，就直接承认这是"包过滤"而不是"状态检测"，并说明区别。

---

### 2:30 – 4:00 连通性与二层/三层验证（1.5 分钟）

**在 PC-B 的 cmd 里：**

```bat
ipconfig
```
> 讲："DHCP 下发 192.168.20.x，网关 .1，DNS 10.20.0.53。"

```bat
ping 10.20.0.10
tracert 10.20.0.10
```
> 讲："跨了三个网段：学生 VLAN20 → 核心 → 出口 → DMZ。"

**在 eNSP 里依次敲（提前开好标签页）：**

```
[CORE] display vlan
[CORE] display ip pool
[CORE] display ip routing-table
[CORE] display ospf peer brief
[R1]   display ip routing-table
```

> 讲："核心学到默认路由，出口学到内网明细路由，OSPF 邻居 FULL。"

---

### 4:00 – 5:30 安全与 NAT（1.5 分钟）

**PC-B：**

```bat
telnet 10.20.0.10 5432
telnet 10.20.0.10 6379
telnet 10.20.0.10 80
```

> Windows 默认没装 telnet 客户端，改用 PowerShell 更稳：
> ```powershell
> Test-NetConnection 10.20.0.10 -Port 5432
> Test-NetConnection 10.20.0.10 -Port 80
> ```
>
> 期望：5432/6379 被拒绝或超时；80 能建立连接。
> 讲："数据库和 Redis 不对学生网段开放，只开放 80。"

**R1：**

```
[R1] display acl 3000
[R1] ping interview.local
[R1] display nat session all
```

> 讲："DNS 解析到 10.20.0.10；访问公网时源地址被 NAT 成 202.100.1.1。"

**PC-B（可选）：**

```bat
ping 202.100.1.2
```

---

### 5:30 – 7:30 业务演示（2 分钟）

**屏幕**：PC-B 浏览器 `http://interview.local`。

只演示 3 个动作，不要贪多：

1. **上传简历 → 看到分析状态从"分析中"变成"已完成"**
   > 讲："文件走 HTTP multipart，后端用 Redis Stream 异步处理。"
2. **进入文字面试 → 回答一题 → 看 AI 追问**
   > 讲："出题和评估都调用大模型，结构化输出 + 重试。"
3. **打开知识库问答 → 看字幕逐字出现**
   > 讲："这是 SSE，不是轮询；下面抓包里能看到 chunked。"

如果时间允许，再进语音面试说一句话，展示 WebSocket 字幕。

---

### 7:30 – 9:00 抓包分析（1.5 分钟）

**屏幕**：Wireshark（提前打开好 `.pcapng`，或用现场抓包）。

按顺序讲 4 个包：

1. **ARP**
   > "跨网段前先解析网关 MAC。"
2. **TCP 三次握手**
   > 过滤 `tcp.flags.syn==1`：SYN → SYN,ACK → ACK。
3. **HTTP 请求**
   > `GET /api/... HTTP/1.1`，看 `Host`、`X-Forwarded-For`。
4. **WebSocket 升级**
   > `GET /ws/voice-interview/1`，`Upgrade: websocket`，响应 `101 Switching Protocols`。
5. **SSE**
   > `Content-Type: text/event-stream`，`Transfer-Encoding: chunked`。

> 一句话收尾："同一条 TCP 连接上，HTTP 负责建连和升级，
> WebSocket 负责双向音频，SSE 负责单向流式文本。"

---

### 9:00 – 10:00 QoS 与总结（1 分钟）

**如果做了 QoS：**

```
[CORE] display traffic policy statistics interface GigabitEthernet0/0/3 inbound
```

> 讲："学生网段限速 4Mbps，语音网段标记 DSCP EF。
> 拥塞时简历上传变慢，但语音仍然流畅。"

**总结（背下来）：**

> "这个课题做了三件事：
> 一是用 eNSP 完成了 VLAN、路由、NAT、ACL、DHCP、DNS 的网络规划；
> 二是把带大模型的 AI 面试平台部署进 DMZ，跑通了 HTTP、WebSocket、SSE；
> 三是用抓包证明了业务流量确实经过了我设计的每一条链路。"

---

## 3. 抓包讲解词（背这一段就够）

| 报文 | 讲解要点 |
|---|---|
| ARP Request/Reply | "跨网段通信前，主机先 ARP 网关 MAC，之后帧的目的 MAC 是网关。" |
| SYN / SYN+ACK / ACK | "TCP 三次握手，确认双方收发能力，协商 MSS 和窗口。" |
| HTTP GET | "请求行、Host、User-Agent；经过 nginx 后多了 X-Forwarded-For。" |
| 101 Switching Protocols | "HTTP Upgrade 升级为 WebSocket，之后是双向帧。" |
| text/event-stream | "SSE 单向流，chunked 分块传输，适合大模型逐字输出。" |
| NAT 前后地址 | "源地址 192.168.20.x 被替换成 202.100.1.1，端口也换了。" |
| ACL 拒绝 | "没有回包或收到 RST，说明在出口被过滤。" |

---

## 4. 应急方案

| 故障 | 处理 |
|---|---|
| eNSP 崩溃 | 切到提前录好的视频 + 截图，继续讲设计 |
| PC-B 掉线 | 改用主机浏览器演示业务，网络部分用截图 |
| 大模型超时/断网 | 切到 LM Studio 本地 Provider，或直接播录屏 |
| 语音面试没声音 | 改成文字面试 + 展示抓到的 WebSocket 帧 |
| 投影看不清 | 提前把命令输出截图放大 |

> **原则**：网络部分可以讲，业务部分可以播，只有"现场卡住 3 分钟"最致命。

---

## 5. 老师可能追问 & 速答

| 问题 | 回答 |
|---|---|
| 为什么不用 USG 防火墙？ | VirtualBox 与 Docker/WSL2 冲突；用 ACL 实现包过滤，功能覆盖，并说明与状态防火墙的区别 |
| eNSP 里怎么跑 Java？ | 不跑。eNSP 只模拟网络设备，应用在真实主机，通过 Cloud 二层接入 DMZ |
| 为什么 VLAN 要划分？ | 广播域隔离、安全隔离、故障域隔离；学生不能直接访问数据库 |
| OSPF 和静态路由怎么选？ | 出口用静态默认路由（简单稳定），内部用 OSPF（可扩展、自动收敛） |
| NAT 之后怎么拿真实 IP？ | X-Forwarded-For 链，但只信任可信代理，否则可伪造（可现场演示） |
| WebSocket 和 HTTP 什么关系？ | 同一 TCP 连接上通过 HTTP Upgrade 握手，之后全双工 |
| 为什么用 SSE 不用 WebSocket？ | 单向流式文本，SSE 更轻；双向语音才用 WebSocket |
| 语音为什么需要 QoS？ | 实时音频对时延/抖动/丢包敏感，需要优先队列 |
| 单点故障怎么办？ | 双核心 VRRP + 双上行链路聚合（选做，讲思路即可） |
| 安全怎么做的？ | DMZ 隔离 + ACL 最小开放 + 应用层限流（Redis Lua）+ 可信代理校验 |
