# -*- coding: utf-8 -*-
"""
生成《AI 面试平台的网络规划与部署》答辩 PPT。

用法：
    python build_ppt.py

输出：
    答辩.pptx   12 页，含演讲者备注
    讲稿.md     从备注导出的逐页口播稿
"""

from pathlib import Path

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_CONNECTOR, MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.oxml.ns import qn
from pptx.util import Inches, Pt

# ---------------------------------------------------------------- 主题
NAVY = RGBColor(0x1F, 0x3A, 0x5F)
TEAL = RGBColor(0x0E, 0x9F, 0x8E)
ORANGE = RGBColor(0xE7, 0x6F, 0x51)
GRAY = RGBColor(0x5A, 0x6B, 0x7B)
LIGHT = RGBColor(0xF2, 0xF6, 0xFA)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
LINE = RGBColor(0xD5, 0xDF, 0xE8)
FONT = "Microsoft YaHei"

OUT = Path(__file__).resolve().parent
prs = Presentation()
prs.slide_width = Inches(13.333)
prs.slide_height = Inches(7.5)


# ---------------------------------------------------------------- 工具
def set_font(run, size, bold=False, color=NAVY):
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.name = FONT
    run.font.color.rgb = color
    rpr = run._r.get_or_add_rPr()
    latin = rpr.find(qn("a:latin"))
    ea = rpr.find(qn("a:ea"))
    if ea is None:
        ea = rpr.makeelement(qn("a:ea"), {})
        if latin is not None:
            latin.addnext(ea)
        else:
            rpr.append(ea)
    ea.set("typeface", FONT)


def textbox(slide, left, top, width, height, lines, size=18, color=NAVY,
            bold=False, align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP, spacing=8):
    """lines: str 或 [(text, size, bold, color), ...]"""
    box = slide.shapes.add_textbox(Inches(left), Inches(top), Inches(width), Inches(height))
    tf = box.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    tf.margin_left = Inches(0.05)
    tf.margin_right = Inches(0.05)
    tf.margin_top = Inches(0.03)
    tf.margin_bottom = Inches(0.03)

    if isinstance(lines, str):
        lines = [(lines, size, bold, color)]

    first = True
    for item in lines:
        text, sz, bd, cl = item
        for chunk in str(text).split("\n"):
            p = tf.paragraphs[0] if first else tf.add_paragraph()
            first = False
            p.alignment = align
            p.space_after = Pt(spacing)
            p.line_spacing = 1.2
            run = p.add_run()
            run.text = chunk
            set_font(run, sz, bd, cl)
    return box


def rect(slide, left, top, width, height, text="", fill=LIGHT, line=LINE,
         color=NAVY, size=14, bold=False, shape=MSO_SHAPE.ROUNDED_RECTANGLE):
    shp = slide.shapes.add_shape(shape, Inches(left), Inches(top), Inches(width), Inches(height))
    shp.fill.solid()
    shp.fill.fore_color.rgb = fill
    shp.line.color.rgb = line
    shp.line.width = Pt(1)
    shp.shadow.inherit = False
    tf = shp.text_frame
    tf.word_wrap = True
    tf.margin_left = Inches(0.06)
    tf.margin_right = Inches(0.06)
    tf.margin_top = Inches(0.03)
    tf.margin_bottom = Inches(0.03)
    tf.vertical_anchor = MSO_ANCHOR.MIDDLE
    first = True
    for chunk in str(text).split("\n"):
        p = tf.paragraphs[0] if first else tf.add_paragraph()
        first = False
        p.alignment = PP_ALIGN.CENTER
        run = p.add_run()
        run.text = chunk
        set_font(run, size, bold, color)
    return shp


def connect(slide, x1, y1, x2, y2, color=GRAY, width=1.5, dash=None):
    conn = slide.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, Inches(x1), Inches(y1),
                                      Inches(x2), Inches(y2))
    conn.line.color.rgb = color
    conn.line.width = Pt(width)
    if dash:
        conn.line.dash_style = dash
    return conn


def add_slide(title, kicker=None):
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    # 顶部色条
    bar = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, prs.slide_width, Inches(0.12))
    bar.fill.solid()
    bar.fill.fore_color.rgb = TEAL
    bar.line.fill.background()

    textbox(slide, 0.6, 0.3, 12.1, 0.75, title, size=30, bold=True, color=NAVY)
    rect(slide, 0.6, 1.12, 1.1, 0.055, fill=ORANGE, line=ORANGE, shape=MSO_SHAPE.RECTANGLE)

    if kicker:
        textbox(slide, 0.6, 1.2, 12.1, 0.35, kicker, size=13, color=GRAY)

    # 页脚
    rect(slide, 0.6, 7.02, 12.13, 0.015, fill=LINE, line=LINE, shape=MSO_SHAPE.RECTANGLE)
    textbox(slide, 0.6, 7.08, 8.0, 0.3,
            "AI 面试平台的网络规划与部署 | 计算机网络技术课程设计",
            size=10, color=GRAY)
    textbox(slide, 11.6, 7.08, 1.1, 0.3, str(len(prs.slides._sldIdLst)),
            size=10, color=GRAY, align=PP_ALIGN.RIGHT)
    return slide


def add_table(slide, rows, left, top, width, height, col_widths=None,
              header_fill=NAVY, font_size=13, row_height=0.45):
    shape = slide.shapes.add_table(len(rows), len(rows[0]), Inches(left), Inches(top),
                                   Inches(width), Inches(height))
    table = shape.table
    if col_widths:
        total = sum(col_widths)
        for i, w in enumerate(col_widths):
            table.columns[i].width = Inches(width * w / total)
    for r, row in enumerate(rows):
        table.rows[r].height = Inches(row_height)
        for c, value in enumerate(row):
            cell = table.cell(r, c)
            cell.text = ""
            cell.margin_left = Inches(0.08)
            cell.margin_right = Inches(0.08)
            cell.margin_top = Inches(0.02)
            cell.margin_bottom = Inches(0.02)
            cell.vertical_anchor = MSO_ANCHOR.MIDDLE
            tf = cell.text_frame
            tf.word_wrap = True
            p = tf.paragraphs[0]
            p.alignment = PP_ALIGN.LEFT if c == 0 else PP_ALIGN.CENTER
            run = p.add_run()
            run.text = str(value)
            if r == 0:
                set_font(run, font_size, True, WHITE)
                cell.fill.solid()
                cell.fill.fore_color.rgb = header_fill
            else:
                set_font(run, font_size, False, NAVY)
                cell.fill.solid()
                cell.fill.fore_color.rgb = WHITE if r % 2 else LIGHT
    return table


def notes(slide, text):
    slide.notes_slide.notes_text_frame.text = text


# ---------------------------------------------------------------- 1 封面
s = prs.slides.add_slide(prs.slide_layouts[6])
bg = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, prs.slide_width, prs.slide_height)
bg.fill.solid()
bg.fill.fore_color.rgb = NAVY
bg.line.fill.background()

rect(s, 0.9, 1.55, 1.4, 0.08, fill=TEAL, line=TEAL, shape=MSO_SHAPE.RECTANGLE)
textbox(s, 0.9, 1.8, 11.5, 1.0, "AI 面试平台的网络规划与部署", size=42, bold=True, color=WHITE)
textbox(s, 0.9, 2.85, 11.5, 0.6, "基于 eNSP 的校园网与数据中心半实物仿真", size=22, color=RGBColor(0xBF, 0xD7, 0xE6))
textbox(s, 0.9, 4.35, 11.5, 1.2, [
    ("计算机网络技术  课程设计答辩", 17, False, RGBColor(0xE8, 0xF1, 0xF7)),
    ("姓名：____________    学号：____________    指导教师：____________", 15, False, RGBColor(0xBF, 0xD7, 0xE6)),
], spacing=10)
textbox(s, 0.9, 6.55, 11.5, 0.4, "2026", size=14, color=RGBColor(0x8F, 0xAA, 0xC0))
notes(s, "各位老师好，我是……。我的课程设计题目是《AI 面试平台的网络规划与部署》。"
         "这个课题把软件工程专业的一个真实项目，放到了 eNSP 构建的校园网里，"
         "既做网络规划，也做端到端验证。下面从选题背景、网络设计、部署实现和测试四个方面汇报。")

# ---------------------------------------------------------------- 2 背景与要求
s = add_slide("选题背景：为什么用这个项目做网络课设", "一个真实业务，三类网络压力")
textbox(s, 0.6, 1.65, 5.6, 0.45, "业务特征", size=17, bold=True, color=TEAL)
textbox(s, 0.6, 2.15, 5.6, 4.4, [
    ("实时语音面试", 18, True, NAVY),
    ("　WebSocket 长连接，双向音频流，对时延和抖动敏感", 14, False, GRAY),
    ("流式回答", 18, True, NAVY),
    ("　SSE 分块推送，要求中间设备不能缓冲", 14, False, GRAY),
    ("大文件与 AI 调用", 18, True, NAVY),
    ("　简历 50MB 上传；大模型请求耗时长，需要长超时", 14, False, GRAY),
], spacing=10)

textbox(s, 6.6, 1.65, 6.2, 0.45, "答辩要求 → 本课题落点", size=17, bold=True, color=TEAL)
add_table(s, [
    ["要求", "落点", "证据"],
    ["网络相关", "VLAN / 路由 / NAT / ACL", "拓扑 + 配置 + 抓包"],
    ["软件工程", "Spring Boot + React 全栈", "完整源码"],
    ["大模型", "多 Provider + RAG + ASR/TTS", "现场演示"],
], left=6.6, top=2.2, width=6.1, height=2.2, col_widths=[1.2, 2.6, 2.3], font_size=12)
notes(s, "先说为什么选这个题。它不是硬凑的：平台有实时语音、流式字幕、大文件上传，"
         "这些业务本身就对网络有要求。右边这张表对应老师的三个要求：网络技术、软件工程代码、大模型使用，"
         "我的做法是把三者串在一条链路上，而不是各讲各的。")

# ---------------------------------------------------------------- 3 应用架构
s = add_slide("应用侧架构：一条请求经过哪些协议", "HTTP → 反向代理 → 应用 → 数据与模型")
rect(s, 0.7, 2.0, 2.0, 0.85, "浏览器\nReact", fill=LIGHT, color=NAVY, size=15, bold=True)
rect(s, 3.3, 2.0, 2.1, 0.85, "Nginx :80\n静态 + 代理", fill=RGBColor(0xE3, 0xF4, 0xF1), color=TEAL, size=15, bold=True)
rect(s, 6.0, 2.0, 2.5, 0.85, "Spring Boot :8080\n业务 / AI 编排", fill=RGBColor(0xFD, 0xEC, 0xE7), color=ORANGE, size=15, bold=True)
rect(s, 9.1, 1.35, 3.2, 0.6, "PostgreSQL + pgvector", fill=LIGHT, size=13)
rect(s, 9.1, 2.15, 3.2, 0.6, "Redis（缓存 + Stream）", fill=LIGHT, size=13)
rect(s, 9.1, 2.95, 3.2, 0.6, "MinIO / S3", fill=LIGHT, size=13)
rect(s, 9.1, 3.75, 3.2, 0.6, "大模型 API（HTTPS）", fill=LIGHT, size=13)

connect(s, 2.7, 2.42, 3.3, 2.42, TEAL, 2)
connect(s, 5.4, 2.42, 6.0, 2.42, TEAL, 2)
for y in (1.65, 2.45, 3.25, 4.05):
    connect(s, 8.5, 2.42, 9.1, y, GRAY, 1.2)
textbox(s, 2.75, 1.72, 1.2, 0.3, "HTTP", size=11, color=TEAL)
textbox(s, 5.45, 1.72, 1.2, 0.3, "/api /ws", size=11, color=TEAL)
textbox(s, 0.7, 3.1, 8.2, 2.6, [
    ("关键协议", 16, True, NAVY),
    ("REST：简历、面试、知识库等业务接口", 14, False, GRAY),
    ("WebSocket：语音面试 /ws/voice-interview/{id}，HTTP Upgrade 后全双工", 14, False, GRAY),
    ("SSE：RAG 流式回答 text/event-stream，chunked 分块", 14, False, GRAY),
    ("S3：对象存储；Redis Stream：异步任务", 14, False, GRAY),
], spacing=6)
notes(s, "这是应用侧的架构。浏览器先到 Nginx 的 80 端口，Nginx 负责静态资源和反向代理，"
         "再转到 Spring Boot 的 8080。业务层往下会访问 PostgreSQL、Redis、MinIO，"
         "以及外部的大模型 API。这张图的作用是说明：后面网络里跑的，就是这些真实协议，不是造出来的流量。")

# ---------------------------------------------------------------- 4 网络需求
s = add_slide("网络需求分析：四个设计目标", "先定指标，再定方案")
cards = [
    ("时延", "语音端到端\n目标 < 150ms", "抖动与丢包会直接\n造成吞字、卡顿"),
    ("带宽", "单文件上传\n上限 50MB", "知识库文档更大\n需要限速与队列"),
    ("安全", "数据库/缓存\n不对学生开放", "DMZ 隔离\n最小权限"),
    ("可用性", "关键链路\n可冗余", "单点故障要能\n秒级切换"),
]
for i, (title, mid, sub) in enumerate(cards):
    x = 0.75 + i * 3.1
    rect(s, x, 1.75, 2.85, 3.3, "", fill=WHITE, line=LINE)
    rect(s, x, 1.75, 2.85, 0.1, fill=[TEAL, ORANGE, NAVY, GRAY][i], line=[TEAL, ORANGE, NAVY, GRAY][i],
         shape=MSO_SHAPE.RECTANGLE)
    textbox(s, x + 0.18, 1.98, 2.5, 0.45, title, size=20, bold=True, color=NAVY)
    textbox(s, x + 0.18, 2.6, 2.5, 1.0, mid, size=14, color=TEAL, bold=True)
    textbox(s, x + 0.18, 3.75, 2.5, 0.9, sub, size=12, color=GRAY)
notes(s, "有了业务特征，就能提炼出四个网络指标：时延、带宽、安全、可用性。"
         "时延按实时语音的通用经验定在 150 毫秒以内；带宽按 50MB 上传设计；"
         "安全上数据库和缓存不能暴露给学生网段；可用性上核心链路要能冗余。"
         "后面的拓扑、ACL、QoS 都是围绕这四个目标展开的。")

# ---------------------------------------------------------------- 5 拓扑
s = add_slide("网络拓扑：出口—核心—接入—DMZ 四层", "eNSP 仿真网络 + 真实主机承载应用")
# 设备
rect(s, 5.75, 1.5, 1.85, 0.62, "ISP-PUB\n202.100.1.2/30", fill=LIGHT, size=11)
rect(s, 5.75, 2.45, 1.85, 0.7, "R1  AR2220\nNAT + ACL", fill=RGBColor(0xFD, 0xEC, 0xE7), color=ORANGE, size=12, bold=True)
rect(s, 3.55, 3.6, 2.0, 0.8, "CORE  S5700\n三层网关 / DHCP", fill=RGBColor(0xE8, 0xF0, 0xF9), color=NAVY, size=12, bold=True)
rect(s, 7.55, 3.6, 2.15, 0.8, "DMZ-SW  S3700\n服务器区", fill=RGBColor(0xE3, 0xF4, 0xF1), color=TEAL, size=12, bold=True)
rect(s, 1.05, 4.95, 1.75, 0.7, "ACC1  S3700\nVLAN10 办公", fill=LIGHT, size=11)
rect(s, 3.35, 4.95, 1.75, 0.7, "ACC2  S3700\nVLAN20 学生", fill=LIGHT, size=11)
rect(s, 1.05, 6.05, 1.75, 0.55, "PC-TEACHER", fill=WHITE, size=11)
rect(s, 3.35, 6.05, 1.9, 0.55, "Cloud1 → PC-B", fill=WHITE, size=11)
rect(s, 7.55, 4.95, 1.95, 0.55, "DNS 10.20.0.53", fill=WHITE, size=11)
rect(s, 9.75, 4.95, 2.75, 1.2, "真实主机\n10.20.0.10\nDocker Compose", fill=RGBColor(0xFD, 0xEC, 0xE7), color=ORANGE, size=11, bold=True)

connect(s, 6.67, 2.12, 6.67, 2.45, ORANGE, 2)
connect(s, 6.0, 3.15, 5.2, 3.6, ORANGE, 2)
connect(s, 7.35, 3.15, 8.3, 3.6, ORANGE, 2)
connect(s, 3.9, 4.4, 1.93, 4.95, NAVY, 1.8)
connect(s, 4.9, 4.4, 4.23, 4.95, NAVY, 1.8)
connect(s, 1.93, 5.65, 1.93, 6.05, GRAY, 1.2)
connect(s, 4.23, 5.65, 4.23, 6.05, GRAY, 1.2)
connect(s, 8.3, 4.4, 8.3, 4.95, TEAL, 1.8)
connect(s, 9.2, 4.4, 10.6, 4.95, TEAL, 1.8)

textbox(s, 9.75, 6.35, 2.9, 0.45, "Cloud2 → 环回网卡", size=10, color=GRAY)
textbox(s, 0.75, 1.5, 3.6, 1.2, [
    ("设计要点", 14, True, TEAL),
    ("出口静态默认路由，内部 OSPF", 11, False, GRAY),
    ("学生 / 办公 / 管理 VLAN 隔离", 11, False, GRAY),
], spacing=3)
notes(s, "这是网络拓扑。从外到内分四层：出口是 R1，做 NAT 和 ACL；"
         "核心是三层交换机，负责 VLAN 网关、DHCP 和 OSPF；接入层是两台 S3700；"
         "右边是 DMZ，放 DNS 和应用服务器。关键的一点是：应用不跑在 eNSP 里，"
         "eNSP 跑不了 Java；应用跑在真实主机 10.20.0.10，通过 Cloud 桥接进 DMZ。"
         "这样既保留了真实的网络路径，又能演示真实业务。")

# ---------------------------------------------------------------- 6 VLAN 规划
s = add_slide("VLAN 与地址规划", "一个业务区域一个广播域")
add_table(s, [
    ["VLAN", "网段", "网关", "用途", "地址分配"],
    ["10", "192.168.10.0/24", "192.168.10.1", "办公 / 教师", "DHCP"],
    ["20", "192.168.20.0/24", "192.168.20.1", "学生 / 真实客户端", "DHCP"],
    ["30", "192.168.30.0/24", "192.168.30.1", "无线（选做）", "DHCP"],
    ["99", "192.168.99.0/24", "192.168.99.1", "设备管理", "静态"],
    ["100", "10.0.1.0/24", "—", "CORE ↔ R1 互联", "静态"],
    ["DMZ", "10.20.0.0/24", "10.20.0.1", "服务器区", "静态"],
], left=0.75, top=1.7, width=7.6, height=3.6, col_widths=[1.0, 2.4, 1.8, 2.6, 1.4], font_size=13)

rect(s, 8.75, 1.7, 3.85, 1.55, "", fill=RGBColor(0xE3, 0xF4, 0xF1), line=TEAL)
textbox(s, 8.95, 1.82, 3.5, 1.3, [
    ("为什么这样分", 14, True, TEAL),
    ("广播域隔离：ARP 广播不跨 VLAN", 11, False, NAVY),
    ("安全隔离：学生网段不能直连数据库", 11, False, NAVY),
    ("故障隔离：接入层问题不影响 DMZ", 11, False, NAVY),
], spacing=3)

rect(s, 8.75, 3.5, 3.85, 1.75, "", fill=WHITE, line=LINE)
textbox(s, 8.95, 3.62, 3.5, 1.5, [
    ("地址规划原则", 14, True, ORANGE),
    ("业务网段 /24，互联段 /30", 11, False, NAVY),
    ("网关统一放在核心（SVI）", 11, False, NAVY),
    ("DNS 与服务器统一在 DMZ", 11, False, NAVY),
], spacing=3)
notes(s, "地址规划遵循两个原则：业务网段用 24 位，互联段用 30 位；网关统一放在核心交换机上，"
         "也就是 SVI。这样接入层只做二层，核心做三层，职责清晰。"
         "VLAN 的划分不只是技术问题，也是安全和故障域问题：学生网段和服务器区必须在不同广播域。")

# ---------------------------------------------------------------- 7 路由与 NAT
s = add_slide("路由与 NAT：出口静态，内部动态", "为什么不是全部 OSPF，也不是全部静态")
textbox(s, 0.75, 1.7, 6.2, 4.9, [
    ("R1 出口", 17, True, ORANGE),
    ("静态默认路由指向模拟公网 202.100.1.2", 14, False, NAVY),
    ("default-route-advertise 把默认路由注入 OSPF", 14, False, NAVY),
    ("NAT outbound：192.168.0.0/16 与 10.20.0.0/24 复用出口地址", 14, False, NAVY),
    ("", 8, False, NAVY),
    ("CORE 核心", 17, True, TEAL),
    ("OSPF area 0 与 R1 建立邻居", 14, False, NAVY),
    ("下发 VLAN10/20/30/99 明细路由", 14, False, NAVY),
    ("通过 OSPF 学到默认路由和 DMZ 网段", 14, False, NAVY),
], spacing=5)

# 右侧小图
rect(s, 7.3, 1.95, 2.0, 0.7, "内网\n192.168.x", fill=LIGHT, size=12)
rect(s, 9.95, 1.95, 2.4, 0.7, "R1 NAT\n202.100.1.1", fill=RGBColor(0xFD, 0xEC, 0xE7), color=ORANGE, size=12, bold=True)
rect(s, 7.3, 3.35, 2.0, 0.7, "公网服务\n202.100.1.2", fill=LIGHT, size=12)
connect(s, 9.3, 2.3, 9.95, 2.3, ORANGE, 2)
connect(s, 8.3, 2.65, 8.3, 3.35, GRAY, 1.8)
textbox(s, 7.3, 4.35, 5.2, 1.5, [
    ("验证命令", 14, True, TEAL),
    ("display ospf peer brief", 12, False, NAVY),
    ("display ip routing-table", 12, False, NAVY),
    ("display nat session all", 12, False, NAVY),
], spacing=4)
notes(s, "路由这块我做了取舍：出口用静态默认路由，因为出口只有一个方向，静态最简单也最稳定；"
         "内部用 OSPF，因为 VLAN 多、以后扩展方便。R1 通过 default-route-advertise 把默认路由下发，"
         "核心就不用自己配默认路由。NAT 配在出口，内网和 DMZ 共用出口地址。"
         "验证的时候看邻居是不是 FULL、路由表有没有默认路由、NAT 有没有会话。")

# ---------------------------------------------------------------- 8 安全
s = add_slide("安全设计：DMZ 隔离与最小开放", "只开放业务需要的端口")
add_table(s, [
    ["访问目标", "端口 / 协议", "策略", "原因"],
    ["Web 前端", "80 / TCP", "允许", "业务入口"],
    ["DNS", "53 / UDP+TCP", "允许", "域名解析"],
    ["ICMP", "—", "允许", "连通性测试"],
    ["PostgreSQL", "5432 / TCP", "拒绝", "数据库不能暴露"],
    ["Redis", "6379 / TCP", "拒绝", "缓存不能暴露"],
    ["MinIO 控制台", "9001 / TCP", "拒绝", "管理面隔离"],
], left=0.75, top=1.7, width=7.9, height=3.8, col_widths=[2.2, 2.0, 1.2, 3.0], font_size=13)

rect(s, 9.0, 1.7, 3.6, 1.5, "", fill=RGBColor(0xFD, 0xEC, 0xE7), line=ORANGE)
textbox(s, 9.2, 1.82, 3.25, 1.3, [
    ("实现方式", 14, True, ORANGE),
    ("R1 上 traffic-filter inbound acl 3000", 11, False, NAVY),
    ("先放行 80/53/ICMP，再拒绝 DMZ 网段", 11, False, NAVY),
], spacing=4)

rect(s, 9.0, 3.55, 3.6, 1.95, "", fill=WHITE, line=LINE)
textbox(s, 9.2, 3.68, 3.25, 1.7, [
    ("为什么不用 USG", 14, True, TEAL),
    ("USG 依赖 VirtualBox", 11, False, NAVY),
    ("与 Docker 的 WSL2/Hyper-V 冲突", 11, False, NAVY),
    ("改用 ACL 实现包过滤，功能覆盖", 11, False, NAVY),
], spacing=4)
notes(s, "安全设计的目标是：学生和办公网只能访问 Web，数据库、Redis、MinIO 控制台一律拒绝。"
         "实现上用的是 R1 的 ACL，先放行 80、53 和 ICMP，最后拒绝整个 DMZ 网段。"
         "这里说明一下为什么没用 USG 防火墙：它依赖 VirtualBox，和 Docker 的 WSL2 冲突，"
         "现场很容易起不来。ACL 是包过滤，不是状态检测，这个区别我后面会如实说明。")

# ---------------------------------------------------------------- 9 部署
s = add_slide("部署实现：Cloud 桥接 + Docker Compose", "eNSP 只做网络，应用跑在真实主机")
steps = [
    ("1", "eNSP 建拓扑", "R1 / CORE / ACC / DMZ-SW + Cloud"),
    ("2", "主机加环回网卡", "10.20.0.10/24，不配网关"),
    ("3", "Cloud 双端口桥接", "端口1 接真实客户端，端口2 接 DMZ"),
    ("4", "Docker Compose 启动", "nginx / app / pg / redis / minio"),
    ("5", "PC-B 通过 DHCP 接入", "浏览器访问 interview.local"),
]
for i, (num, title, sub) in enumerate(steps):
    y = 1.75 + i * 0.92
    rect(s, 0.75, y, 0.55, 0.55, num, fill=TEAL, line=TEAL, color=WHITE, size=16, bold=True, shape=MSO_SHAPE.OVAL)
    textbox(s, 1.45, y - 0.02, 3.2, 0.35, title, size=16, bold=True, color=NAVY)
    textbox(s, 1.45, y + 0.3, 4.4, 0.32, sub, size=12, color=GRAY)

rect(s, 6.4, 1.8, 5.9, 3.7, "", fill=RGBColor(0xF7, 0xFA, 0xFC), line=LINE)
textbox(s, 6.65, 1.95, 5.4, 3.4, [
    ("关键说明", 16, True, ORANGE),
    ("eNSP 运行的是 VRP，不能跑 JVM / Docker", 13, False, NAVY),
    ("Cloud 是二层桥接：把真实网卡“塞进”交换机端口", 13, False, NAVY),
    ("主机保留 Wi-Fi 默认路由，另加 192.168.20.0/24 回程路由", 13, False, NAVY),
    ("nginx 同时代理 /api 与 /ws，SSE 关闭 buffering", 13, False, NAVY),
    ("", 8, False, NAVY),
    ("结果：PC-B 的请求真实经过 接入→核心→出口→DMZ", 13, True, TEAL),
], spacing=6)
notes(s, "部署这一步是半实物仿真的关键。eNSP 里跑的是 VRP，不能跑 Java，所以应用放在真实主机。"
         "主机装一块环回网卡，配 10.20.0.10，不配网关；eNSP 的 Cloud 把它桥接到 DMZ 交换机。"
         "另一个 Cloud 端口接真实网卡，PC-B 插上网线就相当于接在 ACC2 的 VLAN20 上。"
         "这里有个容易忽略的点：主机必须加一条回程路由，否则 PC-B 的请求能进来，响应却走 Wi-Fi 丢了。")

# ---------------------------------------------------------------- 10 抓包
s = add_slide("协议抓包分析：三类关键流量", "在 eNSP 链路上抓包，用 Wireshark 解读")
panels = [
    ("TCP 三次握手", "SYN → SYN+ACK → ACK", "确认双方收发能力\n协商 MSS 与窗口", TEAL),
    ("WebSocket 升级", "GET /ws/... + Upgrade: websocket", "响应 101 Switching Protocols\n之后是双向帧", ORANGE),
    ("SSE 流式输出", "Content-Type: text/event-stream", "Transfer-Encoding: chunked\n逐块下发，不缓冲", NAVY),
]
for i, (title, proto, desc, color) in enumerate(panels):
    x = 0.75 + i * 4.15
    rect(s, x, 1.75, 3.9, 4.1, "", fill=WHITE, line=LINE)
    rect(s, x, 1.75, 3.9, 0.1, fill=color, line=color, shape=MSO_SHAPE.RECTANGLE)
    textbox(s, x + 0.2, 1.98, 3.5, 0.4, title, size=18, bold=True, color=NAVY)
    textbox(s, x + 0.2, 2.5, 3.5, 0.75, proto, size=12, color=color, bold=True)
    textbox(s, x + 0.2, 3.4, 3.5, 1.0, desc, size=12, color=GRAY)
    rect(s, x + 0.2, 4.55, 3.5, 0.9, "截图位置：答辩材料 / 03-抓包", fill=LIGHT, line=LINE, color=GRAY, size=10)

textbox(s, 0.75, 6.1, 11.8, 0.5,
        "一句话总结：同一条 TCP 连接上，HTTP 负责升级，WebSocket 负责双向音频，SSE 负责单向流式文本。",
        size=14, bold=True, color=TEAL)
notes(s, "抓包是我认为最有说服力的部分。第一张是 TCP 三次握手，说明连接是怎么建立的；"
         "第二张是 WebSocket，能看到 Upgrade 头和 101 响应，说明语音面试不是轮询；"
         "第三张是 SSE，能看到 text/event-stream 和 chunked 分块，说明大模型回答是流式下发的。"
         "这三张图分别对应业务里的三个真实场景。")

# ---------------------------------------------------------------- 11 测试
s = add_slide("测试与验证：每条设计都有对应的检查", "命令 + 抓包 + 端到端业务")
add_table(s, [
    ["测试项", "方法", "预期结果"],
    ["DHCP", "PC 自动获取地址", "192.168.x.x，DNS 10.20.0.53"],
    ["跨网段连通", "ping / tracert 10.20.0.10", "路径 192.168.20.1 → 10.0.1.1"],
    ["动态路由", "display ospf peer brief", "邻居状态 FULL"],
    ["NAT", "display nat session all", "存在地址转换表项"],
    ["ACL", "Test-NetConnection 5432 / 80", "5432 失败，80 成功"],
    ["DNS", "ping interview.local", "解析为 10.20.0.10"],
    ["WebSocket", "浏览器 DevTools / 抓包", "101 Switching Protocols"],
    ["SSE", "知识库问答 / 抓包", "chunked 分块逐字输出"],
], left=0.75, top=1.65, width=8.6, height=4.6, col_widths=[2.0, 3.4, 3.2], font_size=12, row_height=0.42)

rect(s, 9.7, 1.7, 2.9, 2.1, "", fill=RGBColor(0xE3, 0xF4, 0xF1), line=TEAL)
textbox(s, 9.9, 1.85, 2.55, 1.85, [
    ("QoS 对比", 14, True, TEAL),
    ("学生网段限速 4Mbps", 11, False, NAVY),
    ("语音网段标记 DSCP EF", 11, False, NAVY),
    ("拥塞时语音仍流畅", 11, False, NAVY),
], spacing=4)

rect(s, 9.7, 4.0, 2.9, 1.7, "", fill=WHITE, line=LINE)
textbox(s, 9.9, 4.12, 2.55, 1.5, [
    ("结果", 14, True, ORANGE),
    ("8 项全部通过", 11, False, NAVY),
    ("关键截图与 pcapng 已归档", 11, False, NAVY),
], spacing=4)
notes(s, "测试这部分我做了 8 项，覆盖二层、三层、安全和应用层。每一项都有明确的命令和预期结果，"
         "不是“感觉通了”。特别说一下 ACL：用 Test-NetConnection 测 5432 应该失败、80 应该成功，"
         "这样能证明策略真的生效，而不是碰巧。最后 QoS 做了限速和 DSCP 标记的对比。")

# ---------------------------------------------------------------- 12 总结
s = add_slide("总结与展望", "三件事：规划、部署、验证")
textbox(s, 0.75, 1.7, 5.8, 0.5, "做了什么", size=18, bold=True, color=TEAL)
textbox(s, 0.75, 2.25, 5.7, 3.6, [
    ("网络：VLAN + OSPF + NAT + ACL + QoS 的完整规划", 14, False, NAVY),
    ("应用：HTTP / WebSocket / SSE 全链路跑通", 14, False, NAVY),
    ("验证：命令 + 抓包 + 端到端业务三重验证", 14, False, NAVY),
], spacing=12)

textbox(s, 7.0, 1.7, 5.6, 0.5, "还可以怎么做", size=18, bold=True, color=ORANGE)
textbox(s, 7.0, 2.25, 5.6, 3.6, [
    ("双核心 VRRP + 链路聚合，消除单点", 14, False, NAVY),
    ("HTTPS + 证书，补齐传输安全", 14, False, NAVY),
    ("IPv6 与 SDN，做下一步扩展", 14, False, NAVY),
], spacing=12)

rect(s, 0.75, 5.55, 11.85, 0.95, "", fill=NAVY, line=NAVY)
textbox(s, 1.0, 5.68, 11.4, 0.7,
        "我的体会：网络不是“配通就行”，而是先有指标、再有设计、最后能被验证。",
        size=17, bold=True, color=WHITE, align=PP_ALIGN.CENTER)
notes(s, "最后总结三句话：第一，网络部分完成了 VLAN、OSPF、NAT、ACL、QoS 的完整规划；"
         "第二，应用部分的 HTTP、WebSocket、SSE 都跑通了；第三，用命令、抓包和业务三重验证。"
         "不足和展望也列了三条。我的体会是：网络不是配通就行，而是先有指标、再有设计、最后能被验证。"
         "我的汇报结束，请老师提问。")

# ---------------------------------------------------------------- 保存
pptx_path = OUT / "答辩.pptx"
prs.save(pptx_path)

# 导出讲稿
lines = ["# 答辩讲稿（按 PPT 页码）\n"]
for i, slide in enumerate(prs.slides, start=1):
    lines.append(f"\n## 第 {i} 页\n")
    text = slide.notes_slide.notes_text_frame.text.strip()
    lines.append(text if text else "（无备注）")
    lines.append("")
(OUT / "讲稿.md").write_text("\n".join(lines), encoding="utf-8")

print(f"OK: {pptx_path}")
print(f"slides: {len(prs.slides._sldIdLst)}")
