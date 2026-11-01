# 选做：升级为 USG6000V 状态防火墙

> 仅在**老师明确要求状态防火墙**时使用。
> 警告：USG6000V 依赖 VirtualBox 5.2.x，与 Docker Desktop 的 WSL2/Hyper-V 冲突。
> 推荐单独一台 Win10 物理机/虚拟机跑 eNSP，或答辩期间关闭 Hyper-V。

---

## 1. 拓扑变化

```
ISP-PUB 202.100.1.2/30
        │
   R1 GE0/0/0 202.100.1.1/30
   R1 GE0/0/1 10.0.0.1/30
        │
   FW1 GE1/0/0 10.0.0.2/30   (untrust)
   FW1 GE1/0/1 10.0.1.1/24   (trust)  ── CORE GE0/0/1 10.0.1.2/24
   FW1 GE1/0/2 10.20.0.1/24  (dmz)    ── DMZ-SW GE0/0/1
```

- 删除原 R1 `GE0/0/2` 直连 DMZ 的链路
- R1 不再做 ACL，只做 NAT 和默认路由
- 安全策略全部迁移到 FW1

---

## 2. FW1 配置

```
system-view
sysname FW1

interface GigabitEthernet1/0/0
 ip address 10.0.0.2 255.255.255.252
 quit
interface GigabitEthernet1/0/1
 ip address 10.0.1.1 255.255.255.0
 quit
interface GigabitEthernet1/0/2
 ip address 10.20.0.1 255.255.255.0
 quit

firewall zone untrust
 add interface GigabitEthernet1/0/0
 quit
firewall zone trust
 add interface GigabitEthernet1/0/1
 quit
firewall zone dmz
 add interface GigabitEthernet1/0/2
 quit

security-policy
 rule name trust_to_dmz_web
  source-zone trust
  destination-zone dmz
  destination-address 10.20.0.10 32
  service http
  action permit
 rule name trust_to_dmz_dns
  source-zone trust
  destination-zone dmz
  destination-address 10.20.0.53 32
  service dns
  action permit
 rule name trust_to_dmz_icmp
  source-zone trust
  destination-zone dmz
  service icmp
  action permit
 rule name dmz_to_untrust
  source-zone dmz
  destination-zone untrust
  action permit
 rule name trust_to_untrust
  source-zone trust
  destination-zone untrust
  action permit
 quit

ip route-static 0.0.0.0 0.0.0.0 10.0.0.1
ip route-static 192.168.10.0 255.255.255.0 10.0.1.2
ip route-static 192.168.20.0 255.255.255.0 10.0.1.2
ip route-static 192.168.30.0 255.255.255.0 10.0.1.2
```

---

## 3. R1 调整

```
system-view
interface GigabitEthernet0/0/1
 ip address 10.0.0.1 255.255.255.252
 quit
undo interface GigabitEthernet0/0/2

ip route-static 0.0.0.0 0.0.0.0 202.100.1.2
ip route-static 10.0.1.0 255.255.255.0 10.0.0.2
ip route-static 10.20.0.0 255.255.255.0 10.0.0.2
ip route-static 192.168.10.0 255.255.255.0 10.0.0.2
ip route-static 192.168.20.0 255.255.255.0 10.0.0.2
ip route-static 192.168.30.0 255.255.255.0 10.0.0.2
```

> NAT 仍保留在 R1：`acl 2000` + `interface GE0/0/0` + `nat outbound 2000`。

---

## 4. 验证

```
[FW1] display security-policy rule all
[FW1] display session table
[FW1] display zone
[PC]  telnet 10.20.0.10 5432      # 被拒绝
[PC]  telnet 10.20.0.10 80        # 放行
```

---

## 5. 常见坑

| 现象 | 处理 |
|---|---|
| USG 一直启动中 | VirtualBox 版本不对；换 5.2.44，关闭 Hyper-V |
| Web 登录 192.168.0.1:8443 失败 | 用 console 线进 CLI 配 |
| 安全策略不生效 | 检查 `display zone` 接口是否加入正确区域 |
| OSPF 跨防火墙不通 | 本方案不用 OSPF 跨防火墙，全部静态路由 |
