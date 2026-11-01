# eNSP 环境搭建与连线

> 本文件是 `README.md` 第 6 节 Step 1 / Step 6 的详细操作手册。
> 目标：30 分钟内把 9 台设备连好、配好、跑通。

---

## 1. 设备清单

| 序号 | eNSP 设备名 | 型号 | 数量 | 说明 |
|---|---|---|---|---|
| 1 | R1 | AR2220 | 1 | 出口路由 / NAT / ACL |
| 2 | CORE | S5700 | 1 | 三层核心 / DHCP / OSPF |
| 3 | ACC1 | S3700 | 1 | 办公接入 |
| 4 | ACC2 | S3700 | 1 | 学生 / 真实客户端接入 |
| 5 | DMZ-SW | S3700 | 1 | 服务器区 |
| 6 | ISP-PUB | Server | 1 | 模拟公网 |
| 7 | DNS-Server | Server | 1 | 内网 DNS |
| 8 | PC-TEACHER | PC | 1 | 办公终端 |
| 9 | Cloud1 | Cloud | 1 | 桥接真实主机 |

> **端口命名提醒**：S3700 的百兆口是 `Ethernet0/0/1~24`，千兆上行是
> `GigabitEthernet0/0/1~2`；S5700 全部是 `GigabitEthernet0/0/1~24`。

---

## 2. 连线表

| 本端 | 端口 | 对端 | 端口 | 链路作用 |
|---|---|---|---|---|
| R1 | GE0/0/0 | ISP-PUB | eth0 | 模拟公网 |
| R1 | GE0/0/1 | CORE | GE0/0/1 | 出口互联 10.0.1.0/24 |
| R1 | GE0/0/2 | DMZ-SW | GE0/0/1 | DMZ 网关 |
| CORE | GE0/0/2 | ACC1 | GE0/0/1 | Trunk 10/99 |
| CORE | GE0/0/3 | ACC2 | GE0/0/1 | Trunk 20/99 |
| ACC1 | E0/0/1 | PC-TEACHER | eth0 | VLAN10 |
| ACC2 | E0/0/1 | Cloud1 | 端口1 | 对接真实 PC-B |
| DMZ-SW | E0/0/1 | Cloud1 | 端口2 | 对接主机环回 10.20.0.10 |
| DMZ-SW | E0/0/2 | DNS-Server | eth0 | 10.20.0.53 |

> 选做：CORE `GE0/0/4` ↔ AC6005，AP 无线覆盖 VLAN30。

---

## 3. Cloud 配置（最容易出错的一步）

### 3.1 先创建环回网卡（Windows 10）

1. `Win + R` → `hdwwiz` → 下一步
2. 选择"手动从列表安装硬件"
3. 选择"网络适配器" → 厂商 `Microsoft` → 型号 `Microsoft KM-TEST 环回适配器`
4. 完成后打开"网络连接"，重命名为 `eNSP-Server`
5. IPv4：`10.20.0.10 / 255.255.255.0`
   - **不要填默认网关**
   - **不要填 DNS**（避免影响主机上网）

### 3.2 配置 Cloud

双击 `Cloud1`：

| 端口 | 绑定网卡 | 端口类型 | 连接对象 |
|---|---|---|---|
| 端口1 | 主机**有线网卡**（Realtek/Intel Ethernet） | GE | ACC2 `E0/0/1` |
| 端口2 | `eNSP-Server`（环回） | GE | DMZ-SW `E0/0/1` |

要点：

- 有线网卡的 IPv4 **取消勾选**（不让 Windows 自己发 DHCP/ARP，避免和 PC-B 抢地址）
- 主机上网走 **Wi-Fi**，互不影响
- 如果网卡绑定失败：先在设备管理器禁用再启用网卡，或重启 eNSP

### 3.3 接线方式

```
[主机有线网卡] ──网线── [PC-B 网卡]
        └── eNSP Cloud 端口1 ── ACC2 E0/0/1（VLAN20）
```

- 直连网线即可（现代网卡支持自动翻转）
- 更稳妥：中间加一台小交换机
- PC-B 的 Wi-Fi 建议关闭，避免默认路由干扰

---

## 4. Server 设备配置

### 4.1 ISP-PUB（模拟公网）

双击设备 → "基础配置"：

| 项 | 值 |
|---|---|
| IPv4 | 202.100.1.2 |
| 子网掩码 | 255.255.255.252 |
| 网关 | 202.100.1.1 |

可选：开启 HTTP 服务，放一个 `index.html`，用于演示"内网访问公网服务"。

### 4.2 DNS-Server

| 项 | 值 |
|---|---|
| IPv4 | 10.20.0.53 |
| 子网掩码 | 255.255.255.0 |
| 网关 | 10.20.0.1 |

打开 "DNS Server" 选项卡，开启服务，添加记录：

```
interview.local    A    10.20.0.10
```

### 4.3 PC-TEACHER

双击 → "基础配置" → 勾选 DHCP，等待获取 `192.168.10.x`。

---

## 5. 主机（PC-A）配置

### 5.1 端口可达性

Docker Compose 默认把端口发布到 `0.0.0.0`，因此 `10.20.0.10:80` 应该可直接访问。

```powershell
docker compose ps
curl http://10.20.0.10
```

如果**本机都访问不了**，加端口转发兜底：

```powershell
netsh interface portproxy add v4tov4 `
  listenaddress=10.20.0.10 listenport=80 `
  connectaddress=127.0.0.1 connectport=80

netsh interface portproxy add v4tov4 `
  listenaddress=10.20.0.10 listenport=8080 `
  connectaddress=127.0.0.1 connectport=8080
```

查看/删除：

```powershell
netsh interface portproxy show all
netsh interface portproxy reset
```

### 5.2 Windows 防火墙

```powershell
New-NetFirewallRule -DisplayName "eNSP-Demo" -Direction Inbound -Protocol TCP `
  -LocalPort 80,8080,9000 -Action Allow -Profile Any
```

> 更简单：把 `eNSP-Server` 和有线网卡的网络位置设为"专用"，答辩期间临时关闭专用网络的防火墙。

### 5.3 主机路由（**必须**）

PC-B 的地址是 `192.168.20.x`，不在主机 `10.20.0.0/24` 的直连网段内。
主机收到请求后，**回复包必须交回 R1 的 DMZ 口 10.20.0.1**，
否则会走 Wi-Fi 默认路由而被丢弃——表现为"TCP 握手成功但页面一直转圈"。

```powershell
# 管理员权限执行
route -p add 192.168.10.0 mask 255.255.255.0 10.20.0.1
route -p add 192.168.20.0 mask 255.255.255.0 10.20.0.1
route -p add 192.168.30.0 mask 255.255.255.0 10.20.0.1
```

查看与删除：

```powershell
route print
route delete 192.168.20.0
```

> 这些是**明细路由**，不影响主机上网：默认路由仍在 Wi-Fi 上。
> 只有访问 192.168.10/20/30.0/24 时才走 eNSP。

---

## 6. 配置下发顺序

1. 全部设备开机（先别连 Cloud）
2. `ACC1.cfg` → `ACC2.cfg` → `DMZ-SW.cfg`（二层先通）
3. `CORE.cfg`（三层网关 + DHCP）
4. `R1.cfg`（路由 + NAT + ACL）
5. 配 Server / DNS / PC
6. 最后接 Cloud，启动 Docker

**粘贴方法**：双击设备进入 CLI → 回车 → 右键"粘贴"。
一次粘 20~30 行为宜；如果出现命令丢失，分段粘贴。

> 脚本里的 `#` 行是注释，VRP 会忽略；`quit` 可省略，但保留更直观。

---

## 7. 验证命令清单（按顺序执行）

### 7.1 二层

```
[ACC1] display vlan
[ACC1] display port vlan
[ACC2] display port vlan
[CORE] display mac-address
[CORE] display stp brief
```

### 7.2 三层与 DHCP

```
[CORE] display ip interface brief
[CORE] display ip pool
[PC-TEACHER] ipconfig
```

期望：PC 拿到 `192.168.10.x`，网关 `192.168.10.1`，DNS `10.20.0.53`。

### 7.3 路由

```
[R1]   display ip routing-table
[R1]   display ospf peer brief
[CORE] display ip routing-table
```

期望：

- `[R1]` 有 `192.168.10.0/20.0/30.0` 的 OSPF 路由
- `[CORE]` 有 `0.0.0.0/0` 和 `10.20.0.0/24` 的 OSPF 路由
- 邻居状态 `FULL`

### 7.4 NAT / ACL / DNS

```
[PC-TEACHER] ping 202.100.1.2
[R1]         display nat session all
[R1]         ping interview.local
```

PC 上验证端口（Windows 用 PowerShell）：

```powershell
Test-NetConnection 10.20.0.10 -Port 5432   # 期望 False / TcpTestSucceeded: False
Test-NetConnection 10.20.0.10 -Port 80     # 期望 True
```

期望：

- ping 公网通，NAT 表有 `192.168.10.x:xxxx -> 202.100.1.1:xxxx`
- `ping interview.local` 解析为 `10.20.0.10`
- `telnet ... 5432` 被拒绝（ACL）
- `telnet ... 80` 能建立连接（或提示 HTTP 服务响应）

### 7.5 端到端

```
[PC-TEACHER] tracert 10.20.0.10
```

期望路径：

```
1  192.168.10.1
2  10.0.1.1
3  10.20.0.10
```

> `tracert` 显示 `10.0.1.1` 而不是 `10.0.1.2`，是因为 R1 是最后一跳网关；
> 若只看到两跳，属于正常现象，讲解时说明"核心是二层转发 + 三层网关"即可。

---

## 8. 抓包点

| 抓包位置 | 能看到什么 | 对应知识点 |
|---|---|---|
| ACC2 `E0/0/1` ↔ Cloud | ARP、DHCP、ICMP | 二层寻址、DHCP |
| CORE `GE0/0/2`（Trunk） | 802.1Q TAG、多 VLAN | VLAN/Trunk |
| CORE `GE0/0/1` ↔ R1 | 跨网段转发、TTL 递减 | 路由转发 |
| R1 `GE0/0/0` ↔ ISP | 源地址已转换为 202.100.1.1 | NAT |
| DMZ-SW `E0/0/1` ↔ Cloud | HTTP、WebSocket、SSE | 应用层协议 |

操作：右键链路 → "抓包" → 自动打开 Wireshark → 保存为 `.pcapng`。

---

## 9. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| Cloud 绑定网卡失败 | 网卡被占用/驱动 | 禁用再启用网卡，重启 eNSP |
| PC-B 拿不到 IP | Cloud 未绑定 / ACC2 端口 VLAN 错 | `display port vlan` 检查；改用静态 IP |
| PC-B 能 ping 网关但 ping 不通 10.20.0.10 | R1 ACL 或路由 | 先 `ping 10.0.1.1`，再查 `display acl 3000` |
| OSPF 邻居卡在 INIT/EXSTART | ACL 拦了组播 / MTU | 确认 ACL 最后有 `permit ip` |
| 主机访问 10.20.0.10 不通 | Docker 只监听 127.0.0.1 | `netsh portproxy` 兜底 |
| PC-B 能 ping 通，但网页一直转圈 | 主机缺少回程路由 | 按 5.3 添加 `route add` |
| 应用看到的客户端 IP 是 172.x | Docker 端口代理丢失源地址 | 正常现象，按"多层 NAT"讲；或改原生 nginx |
| eNSP 内 ping 10.20.0.10 不通，但主机本机可访问 | 环回网卡不响应 ARP | 换 VMware/VirtualBox 的 Host-Only 虚拟网卡并配 10.20.0.10，或换 USB 网卡 |
| 浏览器打开白屏 | 前端构建失败 / nginx 404 | `docker compose logs frontend` |
| 语音面试连不上 | nginx 未代理 `/ws/` | 见 `code-changes.md` 必修 1 |
| WebSocket 403 | Origin 不在白名单 | 见 `code-changes.md` 必修 3 |
| SSE 不逐字输出 | nginx 缓冲 | 加 `proxy_buffering off` |

---

## 10. 备份与恢复

- 拓扑保存为 `interview-guide.topo`，每天备份一次
- 每台设备 `save` 后，把 `display current-configuration` 导出为 `.txt`
- 抓包文件按 `日期-链路-协议.pcapng` 命名
- 答辩前一周录一遍完整演示视频，防止现场设备故障
