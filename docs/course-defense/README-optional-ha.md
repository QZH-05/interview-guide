# 选做：双核心 VRRP + OSPF（高可用）

> 用于回答"单点故障怎么办"。
> 设备增加一台 S5700（CORE2），两台核心通过 VLAN100 互联并跑 OSPF，
> 在 VLAN10/20/30 上做 VRRP，接入层双上行 + 链路聚合。

---

## 1. 拓扑变化

```
              R1 GE0/0/1 10.0.1.1/24
                 │
        ┌────────┴────────┐
        │                 │
   CORE1 GE0/0/1     CORE2 GE0/0/1
   10.0.1.2/24       10.0.1.3/24
        │  GE0/0/5  ═══  GE0/0/5   (VLAN100 互联/心跳)
        │                 │
   Eth-Trunk1 ═══════ Eth-Trunk1
        │                 │
        └────── ACC1 ─────┘
        └────── ACC2 ─────┘
```

---

## 2. CORE1 关键配置

```
vlan batch 10 20 30 99 100

interface Vlanif100
 ip address 10.0.1.2 255.255.255.0
 quit

interface Vlanif10
 ip address 192.168.10.2 255.255.255.0
 vrrp vrid 10 virtual-ip 192.168.10.1
 vrrp vrid 10 priority 120
 vrrp vrid 10 preempt-mode timer delay 20
 quit

interface Vlanif20
 ip address 192.168.20.2 255.255.255.0
 vrrp vrid 20 virtual-ip 192.168.20.1
 vrrp vrid 20 priority 120
 vrrp vrid 20 preempt-mode timer delay 20
 quit

ospf 1 router-id 2.2.2.2
 area 0.0.0.0
  network 10.0.1.0 0.0.0.255
  network 192.168.10.0 0.0.0.255
  network 192.168.20.0 0.0.0.255
  network 192.168.99.0 0.0.0.255
 quit
quit
```

---

## 3. CORE2 关键配置

```
vlan batch 10 20 30 99 100

interface Vlanif100
 ip address 10.0.1.3 255.255.255.0
 quit

interface Vlanif10
 ip address 192.168.10.3 255.255.255.0
 vrrp vrid 10 virtual-ip 192.168.10.1
 vrrp vrid 10 priority 100
 quit

interface Vlanif20
 ip address 192.168.20.3 255.255.255.0
 vrrp vrid 20 virtual-ip 192.168.20.1
 vrrp vrid 20 priority 100
 quit

ospf 1 router-id 3.3.3.3
 area 0.0.0.0
  network 10.0.1.0 0.0.0.255
  network 192.168.10.0 0.0.0.255
  network 192.168.20.0 0.0.0.255
  network 192.168.99.0 0.0.0.255
 quit
quit
```

---

## 4. 接入层链路聚合

```
[ACC1] interface Eth-Trunk1
        port link-type trunk
        port trunk allow-pass vlan 10 99
        mode lacp-static
       interface GigabitEthernet0/0/1
        eth-trunk 1
       interface GigabitEthernet0/0/2
        eth-trunk 1
```

CORE1/CORE2 同理，各建 Eth-Trunk1，分别接 ACC1 的两个上行口。

---

## 5. 验证

```
[CORE1] display vrrp
[CORE1] display vrrp statistics
[CORE1] display ospf peer brief
[ACC1]  display eth-trunk 1
```

**故障演练**：在 CORE1 上 `shutdown` 上行口，观察：

- VRRP 在 3 秒内切换到 CORE2
- 业务短暂中断后恢复
- `display vrrp` 中 CORE2 变 Master

---

## 6. 答辩讲法

> "核心层双机 VRRP 提供网关冗余，接入层双上行链路聚合提供链路冗余，
> 核心之间跑 OSPF 保证路由一致。单台核心或单条链路故障时，
> 业务在秒级内恢复。"
