### 4.3 核心模块三：语音面试实时链路（技术含量最高的一块）

#### 这一节解决的业务问题

**一句话结论：把"面试官当面说话"拆成 7 个能独立观测、独立降级的环节——采音 → 识别 → 判轮次 → 生成 → 合成播放 → 回声防护 → 容错——每环都有超时和兜底，任何一环挂掉都不能让整场面试卡死。**

"像真人"的四条验收标准：

1. **延迟短**：说完到听见面试官开口，理想 2~3 秒。超 5 秒用户会怀疑断线、开始刷新页面。
2. **不抢话**：面试官说话时，麦克风不能把扬声器声音录回去当"用户回答"。最容易崩的一条。
3. **不切碎**："嗯……我想一下"是正常停顿，不能一停顿就判定"说完了"。
4. **断了能接**：ASR 掉一次、网络抖一下，重连后对话历史还在，不重新自我介绍。

旧版被吐槽"关键技术怎么实现的没有说"，根因就是只写了"用了 WebSocket + ASR + TTS"，没写**帧长是多少、为什么是这个数、沉默多久算一句、并发几个 TTS、谁超时谁兜底**。这一节全部补上，每个参数都给取值理由。

---

#### 全链路总览

每个节点都标了对应文件/类。**关键认知：上行是音频，下行是"文本 + 音频"两条并行通道**——字幕（text）和声音（audio）不保证同时到达。

```
【浏览器】
 麦克风
   │ getUserMedia(audio: echoCancellation/noiseSuppression/autoGainControl, sampleRate:16000)
   ↓ AudioRecorder.tsx:148-155          ← 三个开关在这里打开（回声防护第 1 层）
 AudioContext(16000) + Source + Analyser   AudioRecorder.tsx:196-256
   │ Float32 PCM（真实采样率常 48000，由全局量 sampleRate 暴露）
   ↓
 pcm-processor.js:25-37 process()        ← AudioWorkletProcessor，跑在音频渲染线程
   │ ① resample() 线性插值→16000   :46-64
   │ ② float32ToInt16() 定标        :71-78
   │ ③ flushChunks() 攒 3200 采样   :92-129
   ↓ Int16Array(3200).buffer，每 200ms 一块
 AudioRecorder.tsx:239-246 → arrayBufferToBase64() :122-133
   │ base64 字符串（约 8.5KB，体积 +33%）
   ↓
 VoiceInterviewPage.tsx:640-652 handleAudioData
   │ 门控 isAiSpeaking/isSubmitting/!isAsrReady 一律 return ← 回声防护第 2 层
   ↓ voiceInterview.ts:339-351 sendAudio
 ══ WebSocket ══→【后端】VoiceInterviewWebSocketHandler.java
   │ 路径注册：WebSocketConfig.java → "/ws/voice-interview/{sessionId}"
   ↓ handleTextMessage:302-351 → handleUserAudio:476-537
   │ 门控 state.isAiSpeakingOrCooldown() 丢弃 ← 回声防护第 3 层（800ms）
   ↓ QwenAsrService.sendAudio:340-369（awaitReady(1200) → base64 → appendAudio）
   ↓ DashScope 实时 WS：wss://dashscope.aliyuncs.com/api-ws/v1/realtime
【qwen3-asr-flash-realtime · server_vad · silence 2000ms】
   │ .text/.delta → 临时字幕 ｜ .completed → 定稿句
   ↓ QwenAsrService.handleServerEvent:461-534 → onPartial/onFinal
 handleSttResult:539-570（isProcessing 时丢弃迟到段 ← 回声防护第 4 层 :557-562）
   │ final → appendFinalSttSegment → joinSegments() 去重叠 :1378-1408
   ↓ sendSubtitle {"type":"subtitle","text":...,"isFinal":false}
【浏览器】onSubtitle:368-385 → setUserText（可编辑字幕）
   ↓ 用户点「提交回答」 handleSubmitAnswer:344-360
   ↓ {"type":"control","action":"submit","data":{"text":"..."}}
 ══ WebSocket ══→ handleControl:869-893 → setMergeBufferDirectly(text)
   ↓ flushMergedUtteranceToLlm:572-609（isProcessing CAS 单飞锁）
   ↓ voicePipelineExecutor（虚拟线程）
 triggerLlmResponse:618-841
   ├─ getHistory():1226-1267  从 DB 拼「面试官：/候选人：」重建历史
   ├─ DashscopeLlmService.chatStreamSentences:69-155
   │    ├─ onToken  节流 180ms/12 字符 → sendTextMessage（流式字幕）:115-128
   │    └─ onSentence 按终止标点增量切句 → chunkEmitter.submit() :99-109
   ├─ OrderedTtsChunkEmitter:976-1091
   │    ├─ Semaphore(3) 限并发 → QwenTtsService.synthesize（每次新建 WS）
   │    └─ drainChunks() 顺序取 → convertPcmToWav → sendAudioChunk
   └─ finally: aiSpeaking=false；aiSpeakEndAt=now+800ms :837-840
   ↓ {"type":"audio_chunk","data":"<base64 WAV>","index":n,"isLast":false}
 ══ WebSocket ══→【浏览器】handleAudioChunk:221-254
   │ atob → 跳过 44 字节 WAV 头 → Int16 → Float32(/32768) → createBuffer(1,n,24000)
   ↓ playNextChunk:175-195  串行播放，source.onended 接力
 AudioContext(24000).destination → 扬声器
   ↓ 播完 → sendAudioComplete → audio_complete → scheduleChunkDrainCompletion:197-219
   ↓ setAiSpeaking(false) → 门控解除 → 麦克风恢复上行
```

**两条下行的区别（常被追问）**：`type:"text"`（`sendTextMessage:922-928`）是 LLM 流式增量字幕；`type:"audio"`（`sendAudio:904-916`，配 `HTMLAudioElement`）与 `type:"audio_chunk"`（`sendAudioChunk:950-962`，配 `AudioContext`）才是声音。两条并行是历史演进产物——`chunked-audio-enabled` 为 true 时走 chunk 路径（`:653`），但整段路径保留作兜底。

---

#### 环节一：采集——getUserMedia、AudioWorklet 与分块上行

**这一步在干什么**：把麦克风模拟信号变成 ASR 能直接吃的字节流。目标格式 `PCM/16000Hz/单声道/16bit`（`application.yml:229-241` 的 `format: pcm`、`sample-rate: 16000`）。浏览器给的是 Float32、采样率常 48000，所以必须**重采样 + 位深转换 + 分块**。

**涉及的技术**：`getUserMedia` 三开关（`echoCancellation` 回声消除=AEC，把扬声器声音从麦克风信号里减掉；`noiseSuppression` 降噪；`autoGainControl` 自动增益）；**AudioWorklet** = Web Audio 里唯一能跑自定义音频处理代码的机制，在**音频渲染线程**执行，`process()` 每 128 帧调一次，老方案 `ScriptProcessorNode` 跑主线程已废弃；**线性插值重采样**；**float32→int16 定标**；**base64**（WebSocket 文本帧装二进制，体积 +4/3）；WebSocket 全双工上行。

**(1) 三个开关 + 采样率提示**——`frontend/src/components/AudioRecorder.tsx:148-155`：

```tsx
const stream = await navigator.mediaDevices.getUserMedia({
  audio: {
    echoCancellation: true,        // AEC：减掉扬声器回灌，回声防护第 1 层
    noiseSuppression: true,        // 降噪：空调声/键盘声
    autoGainControl: true,         // AGC：说话小声也能识别
    sampleRate: TARGET_SAMPLE_RATE, // 16000，仅"期望值"
  },
});
```

- 为什么开这三个：**不依赖浏览器 AEC，但一定开着**——零成本零延迟，能挡掉大部分回灌，剩下的漏网之鱼才交给上层逻辑。这是四层防护里最便宜的一层。
- **`sampleRate: 16000` 不是保证值**，浏览器有权忽略，实际 `AudioContext` 仍是 48000。所以 `pcm-processor.js` 必须自己重采样——**这就是"写了 sampleRate 还要写重采样"的原因，面试高频考点**。

**(2) Worklet 里干活的三步**——`frontend/public/audio-worklet/pcm-processor.js:25-37`：

```js
process(inputs, outputs, parameters) {
  const input = inputs[0]?.[0];
  if (!input || input.length === 0) {
    return true;                        // 没数据也要 return true，否则处理器被回收
  }
  const resampled = this.resample(input, sampleRate, this.targetSampleRate);
  const pcm = this.float32ToInt16(resampled);
  this.enqueue(pcm);
  this.flushChunks();
  return true;                          // 常驻：true = 别回收我
}
```

`inputs[0][0]` 是第 0 个输入节点的第 0 声道，`?.` 防节点被 disconnect 后的空输入；`sampleRate` 是 `AudioWorkletGlobalScope` 上的**全局量**，不是参数，真实硬件采样率只有这里拿得到；**两处 `return true` 语义都是"让 processor 活着"，返回 `false` 会被 GC，之后再也收不到音频——这是 AudioWorklet 最常见的"录着录着没声了"根因。**

**(3) 重采样：线性插值**——`pcm-processor.js:46-64`：

```js
resample(input, sourceRate, targetRate) {
  if (sourceRate === targetRate) return input;      // 已是 16k，零拷贝返回
  const ratio = sourceRate / targetRate;            // 48000/16000 = 3
  const outputLength = Math.max(1, Math.round(input.length / ratio));
  const output = new Float32Array(outputLength);
  for (let i = 0; i < outputLength; i++) {
    const sourceIndex = i * ratio;                  // 目标第 i 点对应的源浮点下标
    const lower = Math.floor(sourceIndex);
    const upper = Math.min(lower + 1, input.length - 1);
    const weight = sourceIndex - lower;             // 权重 0~1
    output[i] = input[lower] * (1 - weight) + input[upper] * weight;
  }
  return output;
}
```

`sourceIndex` 是浮点下标，`lower`/`upper` 是两侧整数，`weight` 表示"离 upper 多近"，线性加权即插值；`Math.min(lower + 1, ...)` 防最后一个输出点越界。**代价：没有低通滤波，48k→16k 会把 8kHz 以上能量折叠（aliasing）回可听频段。语音能量主要在 300~3400Hz，对识别影响有限——这是主动取舍：想彻底解决要上 FIR 低通，代价是每条音频多一个 128 阶卷积，不值得。**

**(4) float32→int16：负半轴 32768、正半轴 32767**——`pcm-processor.js:71-78`：

```js
float32ToInt16(input) {
  const output = new Int16Array(input.length);
  for (let i = 0; i < input.length; i++) {
    const s = Math.max(-1, Math.min(1, input[i]));   // 先硬裁到 [-1,1]
    output[i] = s < 0 ? s * 0x8000 : s * 0x7fff;     // 负 32768，正 32767
  }
  return output;
}
```

先裁到 `[-1,1]`：浮点音频叠加后偶有超界，不裁乘 32768 会溢出。**Int16 是补码，范围 `-32768 ~ +32767`；正半轴若也乘 32768，`s = 1.0` 得 32768 → 超范围回卷成 -32768 → 听感是一声爆响。** 这是音频编程经典坑，必须能讲。`Int16Array` 赋值是截断（非四舍五入），引入约 ±0.5 LSB 量化噪声，可忽略。

**(5) 3200 采样分块 = 200ms**——`pcm-processor.js:10-17` 与 `:92-129`：`this.samplesPerChunk = 3200; // 200ms at 16kHz`，即 `16000 × 0.2`。

- **为什么不是每 128 帧（≈2.7ms）发一块**：一次发送 = base64 编码 + JSON 序列化 + WS 帧开销。128 帧一块，1 秒发 375 次，CPU 全烧在编码上；200ms 一块，1 秒 5 次，开销可忽略。
- **为什么不是 1 秒**：会让 ASR 拿到音频的延迟直接 +1s，而实时 ASR 要持续喂流才能做 VAD。
- `flushChunks()` 的双层 `while` 处理"每次只来 128 帧、攒到 3200 才发"的攒批；`this.pending[0] = head.subarray(take)` 保证半块不丢，可跨调用拼接。

**(6) 上行唯一一处 base64**——`AudioRecorder.tsx:239-246` 与 `:122-133`：

```tsx
workletNode.port.onmessage = (event) => {
  const buffer = event.data as ArrayBuffer;
  const base64 = arrayBufferToBase64(buffer);
  onAudioData(base64);
};
```
```tsx
const arrayBufferToBase64 = (buffer: ArrayBuffer): string => {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  const chunkSize = 0x8000;                    // 32768
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary);
};
```

- `port.postMessage(chunk.buffer)` 是**结构化克隆**而非 transfer（`pcm-processor.js:117-119` 注释写着 `clone mode to avoid transferable issues`）。克隆多拷贝一次内存，换来主线程独占 buffer，不与音频线程争。
- 分块 32768 的原因：`String.fromCharCode(...chunk)` 用展开语法传参，参数个数受栈限制（约 65535）。3200 个采样一次就能过，写成通用工具函数时按 32768 分块更安全。
- **代价**：3200 个 Int16 = 6400 字节，base64 后约 8533 字节（+33%），200ms 一块 ≈ 42KB/s。`VoiceInterviewWebSocketHandler.java:147-148` 的注释就是这个估算，并据此把消息上限设成 256KB。

**出问题会怎样**：`navigator.mediaDevices` 在非 HTTPS 下是 `undefined`（localhost 例外），`AudioRecorder.tsx:135-273` 的 catch 会 `alert`；不支持 AudioWorklet 时 `:197-200` 抛 `"当前浏览器不支持 AudioWorklet，请使用新版 Chrome/Edge"`；**忘了 `gainNode.connect(audioContext.destination)` 会让 `process()` 永不被调用**（Web Audio 图只在有节点连向 destination 时才被拉取），`:249-256` 的 `gainNode.gain.value = 0` 正是"接通但静音"，既防啸叫又让图动起来。

---

#### 环节二：识别——DashScope 实时 ASR、服务端 VAD 与多段拼接

**这一步在干什么**：把持续 PCM 字节流变成中文文本，并判断"用户这句说完了没有"。

**涉及的技术**：**阿里云百炼（DashScope）实时 ASR**，模型 `qwen3-asr-flash-realtime`，WebSocket 双向流（`application.yml:229-241`）；**流式 ASR** = 音频边发边识别，不等整段录完；**服务端 VAD**（Voice Activity Detection，语音活动检测）= 由 ASR 服务端判断还有没有人在说话，连续静音就切句并回事件；**两路字幕**（`text`/`delta` 临时可能被改写，`completed` 定稿不再变）；**多段拼接**（VAD 会把一次长回答切成多段、各回调一次 `onFinal`，服务端拼成整句再交 LLM）。

**(1) 核心配置与取值理由**——真值在 `application.yml:229-241`，Java 默认在 `VoiceInterviewProperties.java:126-137`：

| 配置项 | yml 真值 | Java 默认 | 理由 |
|---|---|---|---|
| `model` | `qwen3-asr-flash-realtime` | 同 | flash 档 = 便宜 + 低延迟，面试不需最高精度 |
| `language` / `format` | `zh` / `pcm` | 同 | 固定中文省语种检测；PCM 不压缩，省服务端解码，换 32KB/s 上行 |
| `sample-rate` | `16000` | `16000` | ASR 原生采样率，与前端重采样目标一致 |
| `turn-detection-type` | `server_vad` | 同 | 服务端判定，客户端不用自己实现 VAD |
| `turn-detection-threshold` | `0.0` | `0.0f` | **最低阈值 = 最容易判成"有人说话"**，宁多切不漏字 |
| `turn-detection-silence-duration-ms` | `${APP_VOICE_ASR_SILENCE_MS:2000}` | `1000`（被 yml 覆盖） | 静音 2000ms 才判一句结束 |

**2000ms 的取值理由（必问）**：yml 注释原文"静音多久判为一句结束；中文面试思考停顿常见 2-3s，设 2000 避免句中切段"。展开：设 500ms，"我用 Redis……（想一下）……做了缓存"会被切成两句，LLM 收到半句；设 2000ms 则换气和思考停顿都被吃进去，代价是每轮说完要多等 2 秒才出定稿字幕；设 4000ms 用户说完要等 4 秒才有反应，体验发木。**注意 `threshold: 0.0` 与 `silence: 2000` 是一对反向参数**：阈值放松（容易判"有人说话"）配合静音拉长 = 多收音频、少切句，两者目标一致。

**(2) 连接建立的两个必讲细节**——`QwenAsrService.java:216-325`：

```java
// :269 —— 先 put 进 sessions，再异步 connect
sessions.put(sessionId, asrSession);
```
```java
// :249-260 —— onClose 里防误删
sessions.compute(sessionId, (id, existing) -> {
    if (existing != null && closed != null && existing.getConversation() == closed) {
        return null;      // 只有"关掉的就是当前这条连接"才移除
    }
    return existing;      // 否则保留新连接
});
```

- **先 put 的原因**：连接是异步建的，若等 `connect()` 返回再 put，中间 `hasActiveSession()` 返回 false，而前端已经在推音频了，会被全拒。**先挂号再看病。**
- **`onClose` 那段是本项目最值钱的一处 bug 修复**，注释原文写着「避免重连后旧 onClose 误删新连接（典型「第三轮起无声」根因）」。场景：网络抖动触发重连，新连接已 put 进 map，此时**旧连接才慢吞吞触发 onClose**，无条件 `remove` 就会把**新连接**删掉 → 之后音频全发不进去 → 现象是"第三轮开始面试官不说话了"。修法是比对引用。
- 之后还有 `if (sessions.get(sessionId) != asrSession) return;`（`:293-300`），防**已被替换的陈旧连接**误标 ready。

**(3) 发送音频：锁 + 就绪等待**——`QwenAsrService.java:340-369`：

```java
public void sendAudio(String sessionId, byte[] audioData) {
    AsrSession session = sessions.get(sessionId);
    if (session == null) {
        throw new IllegalStateException("No active session found: " + sessionId);
    }
    if (!session.awaitReady(1200)) {
        throw new IllegalStateException("ASR session not ready: " + sessionId);
    }
    String audioBase64 = Base64.getEncoder().encodeToString(audioData);
    session.getConversation().appendAudio(audioBase64);
}
```

- `awaitReady(1200)`：`connect()` + `updateSession()` 是异步的，最多等 1200ms。**为什么 1200**：200ms 一块 ≈ 6 块，再等用户已感知卡顿。
- **这是第二次 base64**：前端 base64 → 后端解码 bytes → `appendAudio` 又要 base64。看似浪费一次，换来"服务端内部用 `byte[]` 传递、可做大小统计与截断判断"的解耦。
- 抛 `IllegalStateException` 而不是吞掉：注释（`:355-365`）写「交给 WebSocket 层 restart，不在此重复 onError」——**ASR 服务只报错，重连策略属编排层**。

**(4) 事件分发与兼容性补丁**——`QwenAsrService.java:461-534`：

| DashScope 事件 | 本项目动作 |
|---|---|
| `session.created` / `session.updated` | 只打日志 |
| `...input_audio_transcription.completed` | 取 `transcript` → `onFinal.accept(...)`（定稿） |
| `...input_audio_transcription.text` / `.delta` | `dispatchPartialTranscript` → `onPartial.accept(...)` |
| `...input_audio_transcription.failed` | 只 log.error，不中断 |
| `error` | `String.format("ASR Error [%s/%s]: %s", errorType, errorCode, errorMessage)` → `onError.accept(...)` |

`extractTranscriptPayload`（`:560-604`）依次尝试四个字段：顶层 `transcript` → `text` + `stash` 拼接 → `delta`（字符串或对象内取 `text`/`transcript`）→ `item.transcript`。注释引用了阿里云 qwen-asr-realtime 的 server events 文档，说明 `text` 是"已确认前缀"、`stash` 是"草稿后缀"，**两段拼起来才是当前完整识别文本——这是兼容性补丁，不是冗余**。

**(5) 多段拼接：`mergeBuffer` 与 `joinSegments` 去重叠**——本节技术含量最高的函数，`VoiceInterviewWebSocketHandler.java:1378-1408`：

```java
public void appendFinalSttSegment(String segment) {
    if (segment == null || segment.trim().isEmpty()) return;
    mergeBuffer.updateAndGet(prev -> {
        if (prev == null || prev.isEmpty()) {
            mergeStartedAt.set(System.currentTimeMillis());
            return segment;                 // 第一段直接放
        }
        return joinSegments(prev, segment); // 后续段走去重叠
    });
    markSttActivity();
}

private static String joinSegments(String previous, String next) {
    String prev = previous.trim(), nxt = next.trim();
    if (nxt.equals(prev) || nxt.startsWith(prev)) return nxt;      // A 新段覆盖旧段
    if (prev.endsWith(nxt))                       return prev;     // B 新段已被旧段包含
    if (prev.endsWith("。") || prev.endsWith("！") || prev.endsWith("？")
        || prev.endsWith(".") || prev.endsWith("!") || prev.endsWith("?")) {
        return prev + " " + nxt;                                   // C 旧段已收句，空格连接
    }
    return prev + "，" + nxt;                                      // D 旧段未完句，逗号连接
}
```

- **它解决什么问题**：VAD 每切一段回调一次 `onFinal`，而实时 ASR 的切段结果**天然重叠**——用户说"我做的项目是一个"，段 1 可能是"我做的项目是"、段 2 是"我做的项目是一个推荐系统"（带上下文重发）。无脑 `prev + next` 会拼出"我做的项目是我做的项目是一个推荐系统"，LLM 直接懵。
- **情况 A**（`nxt.startsWith(prev)`）：新段是旧段超集，丢旧段，最常见。**情况 B**（`prev.endsWith(nxt)`）：新段已在旧段尾部，忽略，"重复回放最后几个字"。
- **情况 C/D**：内容真不同，只选连接符。**为什么不用直接拼接**：中文没有词间空格，"我想学新技术我很喜欢"会让 LLM 断句困难，逗号等于显式标断点。
- **代价/已知不足**：**只处理前缀/后缀重叠，不处理中间重叠**。真正的解法是最长公共子串对齐，O(n·m)，在每次回调里跑不值得。

**(6) 临时字幕复用同一套去重叠**——`VoiceInterviewWebSocketHandler.java:1362-1370` 附近 `getMergeBufferPreviewWithPartial(partial)`：confirmed 为空返回 partial，partial 为空返回 confirmed，否则 `joinSegments(confirmed, current)`。**临时字幕和定稿合并共用同一套算法**，所以用户看到的字幕不会在"临时→定稿"瞬间跳变重排——两边若用不同算法，字幕会闪烁。

**出问题会怎样**：`apiKey` 为空时 `@PostConstruct init()`（`:117-123`）直接抛 `IllegalStateException("API key must be configured before initializing QwenAsrService")`——**启动即失败，刻意的 fail-fast**；`session == null` 抛 `"No active session found"`，被 `shouldRecoverAsrConnection(ex)`（`:400-403`）匹配后触发重连；就绪超时抛 `"ASR session not ready"`，被 `isAsrNotReady(ex)`（`:405-412`）匹配后**debug 级丢弃该块**（连接正在建，丢一两块无损）；拼接失败的症状是 LLM 收到重复/断裂句子，**目前没有检测手段**。

---

#### 环节三：轮次判断——三层接力，最终选择手动提交

**这一步在干什么**：判断"用户这轮说完没有"，决定何时把文本交给 LLM。判早了 LLM 答半句话、用户被打断；判晚了用户干等。

**三层接力**：① **服务端 VAD**——数静音，2000ms 无语音即回 `completed`；② **多段拼接**——`joinSegments` 合成完整回答；③ **用户手动提交**——前端「提交回答」按钮才发给 LLM（`VoiceInterviewPage.tsx:344-360`）。

**(1) 手动提交的服务端入口**——`VoiceInterviewWebSocketHandler.java:869-893`：

```java
case "submit":
    Map<String, Object> data = control.getData();
    Object textObj = data == null ? null : data.get("text");
    if (textObj instanceof String submitted && !submitted.isBlank()) {
        state.setMergeBufferDirectly(submitted);   // 用户编辑过的文本覆盖 ASR 结果
    }
    flushMergedUtteranceToLlm(sessionId);
    break;
```

`setMergeBufferDirectly(submitted)` 让**用户在前端字幕框改过的文本优先于 ASR 原始结果**——"ASR 把 Redis 识别成瑞迪斯"因此可修，是手动提交方案白拿的好处。`instanceof String submitted` 是 Java 21 pattern matching，同时完成类型判断与绑定。

**(2) 单飞锁 + 400ms 防重入**——`VoiceInterviewWebSocketHandler.java:572-609`：

```java
if (!state.isProcessing().compareAndSet(false, true)) {
    utteranceMergeScheduler.schedule(                     // 已在处理 → 400ms 后重试
        () -> flushMergedUtteranceToLlm(sessionId), 400, TimeUnit.MILLISECONDS);
    return;
}
String userText = state.takeMergeBufferAndClear();
if (userText == null || userText.isBlank()) {
    state.isProcessing().set(false);
    return;
}
voicePipelineExecutor.execute(() -> {
    state.setProcessingThread(Thread.currentThread());
    try { triggerLlmResponse(sessionId, session, userText, state); }
    finally { state.isProcessing().set(false); state.setProcessingThread(null); }
});
```

- `compareAndSet(false, true)` 是 **CAS 单飞锁**：同一会话同时只允许一轮生成。用户连点两次提交，第二次走 `schedule(400ms)` 而非并发跑两轮。**为什么重试而不是丢弃**：用户点两次的意图是"我补充了内容要重发"，丢弃会让他以为没生效。
- `takeMergeBufferAndClear()` **原子取走并清空**（内部 `getAndSet("")`），避免拼接中途新到的 ASR 段混进这一轮。
- `setProcessingThread(Thread.currentThread())` 是为了**连接断开时能中断正在跑的 LLM/TTS**（`afterConnectionClosed:366-393` 调 `removedState.getProcessingThread().interrupt()`）。
- `voicePipelineExecutor = Executors.newVirtualThreadPerTaskExecutor()`（`:75`）：LLM/TTS/JDBC 都是阻塞操作，**Java 21 虚拟线程**一个任务一个线程。**为什么不跑在那 2 个调度线程上**：`utteranceMergeScheduler` 只有 2 个线程（`:97-106`），LLM 调用挂在上面两个会话就能堵死。

**(3) "防过早"四个参数——必须给一个诚实的答案**

yml 里确实配了四个看起来就是"防过早"的参数（`application.yml:256-263`）：

| 参数 | 值 | yml 注释声明的含义 |
|---|---|---|
| `user-utterance-debounce-ms` | `${APP_VOICE_USER_UTTERANCE_DEBOUNCE_MS:2500}` | "用户说完一小段 STT 定稿后再等多久才触发面试官回复；需大于 VAD 静音阈值" |
| `min-silence-before-commit-ms` | `2500` | 提交前的最短静音 |
| `min-commit-chars` | `20` | 提交所需最少字符数 |
| `max-wait-for-continuation-ms` | `7000` | 等待用户继续说下去的最长时间 |

**它们的设计意图**（一套"自动提交"方案）：① ASR 给定稿段后不立刻提交，等 `user-utterance-debounce-ms`（2500ms）看还有没有下一段；② 期间若来新段则重置定时器，但总等待不超过 `max-wait-for-continuation-ms`（7000ms）；③ 提交前检查累计长度 ≥ `min-commit-chars`（20 字）——太短说明只是"嗯""对"，不该交 LLM；④ `min-silence-before-commit-ms`（2500ms）必须**大于** VAD 的 2000ms，否则"VAD 还没判句结束，这边已经急着提交了"。四个数的关系：**2500 > 2000 留 500ms 缓冲**；20 字 ≈ 说 4~5 秒中文（正常语速 4~5 字/秒），低于这个量级不值得单独回答一轮；7000ms 是"用户开始说了但卡住"的最长容忍，再等面试就僵住。

**但是，这四个参数在当前代码里是死配置。** 全仓 grep `app/src/main/java`，只有 `VoiceInterviewProperties.java` 里的字段声明与 getter，**没有任何业务代码读取**。**所以真实的轮次判断只有两层**：VAD 2000ms 切段 → `mergeBuffer` 拼接 → 等用户点提交。第三层"自动提交"从来没被接上。

**为什么最终选了手动提交**：
1. **准确率换不来**。VAD + 拼接的自动判断在"用户说了一长段、中间思考 3 秒"与"用户说完但还在想"这两种情况上**无法区分**——都是 2 秒以上静音。参数怎么调都是按下这个坑浮起那个坑。
2. **可编辑性是白拿的收益**。手动提交顺带给了"用户改错别字"的能力（`setMergeBufferDirectly`）。自动提交想加这个能力得做"倒计时 3 秒内可撤销"，复杂度翻倍还更容易误操作。
3. **更符合面试心理**。候选人答技术题常"要先组织语言"，自动提交会让他觉得被催。手动提交把节奏控制权还给用户，这也解释了 `VoiceInterviewPage.tsx:714-737` 两段提示文案为什么写"说完后点击提交回答"。

**代价**：用户忘了点提交则面试永远停在那里，服务端兜底是 `@Scheduled(fixedRate = 30000) checkPauseTimeout()`（`:1127-1145`）——4 分 30 秒警告、5 分钟超时暂停会话，**兜底时间非常长**，体验上就是"卡住了要等 5 分钟"。

**出问题会怎样**：不点提交 → 4:30 收到 `pause_timeout_warning`，5:00 被 `handlePauseTimeout`（`:1179-1211`）暂停 + `close(GOING_AWAY)`；提交撞上 AI 说话 → 前端 `canSubmit`（`:714`）用 `!isAiSpeaking && !isSubmitting` 挡在按钮层，绕过也还有服务端 `:557-562` 的迟到丢弃；并发进入 → CAS 挡住，400ms 后重试。

---

#### 环节四：生成——流式 LLM 与手工重建对话历史

**这一步在干什么**：拿到用户这轮回答，生成面试官下一句话。

**涉及的技术**：**流式生成**（`chatClient.prompt()...stream().content()` 返回 `Flux<String>`，一边生成一边推 token）；**首 token 延迟 vs 总延迟**（流式不降总时间，但把首字可见时间从 3~5 秒降到 300~800ms）；**句子级切分**（token 是碎片可能含半个词半个标点，TTS 需要完整句子）；**对话历史重建**（LLM 无状态，要把之前几轮拼回 prompt）。

**(1) 流式主循环 + 双重节流 + 增量切句**——`DashscopeLlmService.java:69-155`：

```java
int emitIntervalMs = Math.max(80, voiceInterviewProperties.getAiStreamPushIntervalMs()); // 180
int minCharsDelta = Math.max(4, voiceInterviewProperties.getAiStreamMinCharsDelta());    // 12

chatClient.prompt().system(ctx.systemPrompt()).user(ctx.userPrompt())
    .stream().content()
    .doOnNext(token -> {
        raw.append(token);
        if (onSentence != null && hasTerminalSince(token)) {
            String normalized = normalizeRealtimeText(raw.toString());
            int currentEnd = normalized.length();
            if (currentEnd > lastSentenceEnd.get()) {
                String sentence = normalized.substring(lastSentenceEnd.get()).trim();
                if (!sentence.isEmpty()) onSentence.accept(sentence);   // 交给 TTS
                lastSentenceEnd.set(currentEnd);
            }
        }
        long elapsedMs = System.currentTimeMillis() - lastEmitAt.get();
        if (elapsedMs >= emitIntervalMs && norm.length() - lastEmitLength.get() >= minCharsDelta) {
            onToken.accept(norm);                                       // 节流推送字幕
            lastEmitAt.set(System.currentTimeMillis());
            lastEmitLength.set(norm.length());
        }
    })
    .blockLast();
```

- `emitIntervalMs = 180`：**`application.yml` 没有显式配这个键**，走 `VoiceInterviewProperties.java:29-53` 的 Java 默认值。**为什么 180ms**：前端每收一次 `text` 就 `setState` 重渲染，不节流一秒可能推 50 次，React 掉帧；180ms ≈ 每秒 5~6 次，肉眼看是连续打字效果。`minCharsDelta = 12` 是**双条件节流**——光有时间不够，模型有时 200ms 只吐 2 个字，推过去是一个一个字蹦。`Math.max(80/4, ...)` 是给配置加下界，防止有人在 yml 填 0 把节流关掉。
- **句子切分的核心是 `lastSentenceEnd` 游标**，记录"已交给 TTS 的规范化文本长度"。**为什么必须用长度游标而不是直接切 token**：`normalizeRealtimeText()` 会做 `replace("**","")`、`replaceAll("\\s+"," ")` 等**改变字符串长度**的操作，token 边界和规范化后的位置对不上，只有"整串长度"这个坐标系稳定。**这是本项目最容易讲错的细节。**
- `.blockLast()` 把响应式流**阻塞**住等结束。整段跑在虚拟线程里，阻塞不浪费平台线程；**为什么不用 `subscribe` 异步**：后面还要在同一调用里等 TTS、落库，阻塞式写法顺序清晰。

**(2) 流结束后补发尾句**——`DashscopeLlmService.java:132-146`：`normalized.length() > lastSentenceEnd.get()` 时把剩余部分 `onSentence.accept(remaining)`，再 `optimizeForVoice(normalized)` 后 `onToken.accept(optimized)`。流结束时最后一句很可能没有终止标点（模型说着说着截断），**不补发则最后一句永远没有声音**；推一次 `optimized` 让前端字幕从"节流中的中间态"变成干净定稿。

**(3) 历史重建：为什么不用 `MessageChatMemoryAdvisor`**——`DashscopeLlmService.java:157-179`：

```java
private PromptContext buildPromptContext(String userInput, VoiceInterviewSessionEntity session,
                                        List<String> conversationHistory) {
    String resumeText = null;
    if (session.getResumeId() != null) {
        resumeText = resumeRepository.findById(session.getResumeId())
            .map(ResumeEntity::getResumeText).orElse(null);
    }
    String systemPrompt = promptService.generateSystemPromptWithContext(session.getSkillId(), resumeText);

    StringBuilder userPrompt = new StringBuilder();
    userPrompt.append("【之前的对话】\n");
    if (conversationHistory != null && !conversationHistory.isEmpty()) {
        for (String message : conversationHistory) {
            userPrompt.append(promptSanitizer.sanitize(message)).append("\n");
        }
    } else {
        userPrompt.append("（这是第一轮对话）\n");
    }
    userPrompt.append("\n【当前对话】\n");
    userPrompt.append("用户：").append(
        promptSanitizer.wrapWithDelimiters("input", promptSanitizer.sanitize(userInput)));
    return new PromptContext(systemPrompt, userPrompt.toString());
}
```

历史上游在 `VoiceInterviewWebSocketHandler.getHistory`（`:1226-1267`）：把 DB 消息还原成 `"面试官：" + aiText` / `"候选人：" + userText` 字符串列表，并用 `pendingAiQuestion` 处理"AI 先问、用户后答"的错位配对。

**三个理由（背下来）**：
1. **存储位置不对**。advisor 把历史存进它自己的 `ChatMemory`（内存版或 JDBC 版），而语音面试的历史**必须落 PostgreSQL**——前端要"面试记录/历史会话/恢复面试"（`voiceInterview.ts:226-241` 的 `getAllSessions`、`VoiceInterviewPage.tsx:558-615` 的 `handleResumeSession`），且 `endSession` 后要用这些消息跑 AI 评估。用 advisor 等于**同一份数据存两遍、还要保证一致**。
2. **一问一答的配对逻辑要自己控**。本项目 DB 里是"一条记录 = 一问一答"（`VoiceInterviewService.java:238-285` 的 `fillLatestUnansweredQuestion` 回填），而面试官**先开场提问**（`triggerOpeningQuestionIfNeeded`，此时 `userRecognizedText` 为 null），用户下一轮才回答——会出现"AI 消息还没配对用户消息"的中间态。advisor 只会机械按序追加，处理不了这个错位。
3. **注入位置要可控**。本项目把历史放在 **user message 里的 `【之前的对话】` 段落**，而不是作为独立的 `UserMessage/AssistantMessage` 列表——这样历史文本会被 `promptSanitizer.sanitize()` 洗一遍，当前输入被 `wrapWithDelimiters("input", ...)` 包裹。用 advisor 就没这个控制点。另外 `application.yml:141-148` 的 `message-chat-memory-enabled: ${APP_AI_ADVISORS_MESSAGE_CHAT_MEMORY_ENABLED:false}` **默认关闭**，全局一致。

**代价**：手工拼字符串**丢了结构化角色信息**，LLM 只能从"面试官：/候选人："前缀猜角色，多轮后更容易"串角色"；而且长会话 token 线性增长（`message-chat-memory-max-messages: 120` 这个上限**在当前手写路径下不生效**，调用处也不做截断）——**这是一条真实的短板。**

**(4) 语音专用裁剪：`ai-question-max-chars: 120`**——`DashscopeLlmService.java:199-223`（`TERMINAL_PUNCTUATION = "。！？；!?;."` 在 `:25`，**含分号**）：

```java
private String optimizeForVoice(String content) {
    if (content == null || content.isBlank()) return "请继续。";
    int maxChars = Math.max(80, voiceInterviewProperties.getAiQuestionMaxChars());  // 120
    if (content.length() <= maxChars) return content;
    String truncated = content.substring(0, maxChars);
    int lastTerminal = -1;
    for (int i = truncated.length() - 1; i >= 0; i--) {
        if (TERMINAL_PUNCTUATION.indexOf(truncated.charAt(i)) >= 0) { lastTerminal = i; break; }
    }
    if (lastTerminal >= maxChars / 2) return truncated.substring(0, lastTerminal + 1); // 截到句末
    return truncated + "…";                                                            // 无可截句末
}
```

- `Math.max(80, ...)`：配置下界 80 字，防被调小到无法表达完整问题。
- **为什么是 120 字**：中文口语约 4~5 字/秒，120 字 ≈ 24~30 秒语音。而 `VOICE_RESPONSE_CONSTRAINTS` 要求"总长度 2-4 句"，一轮问题通常 60~100 字。给到 120 是**留追问余量**，同时硬性保证"任何一轮语音不超过 30 秒"——这个上限直接决定用户等待体验的下限。
- **为什么不截到 120 就停**：半个句子合成出来莫名其妙，比截短更糟，所以从尾部向前找终止标点。`lastTerminal >= maxChars / 2`：标点必须在后半段（≥60 字）才接受，否则说明前 60 字里就有句号、截出来太短，不如保留 120 字加省略号。
- **代价**：硬截断会**砍掉追问**（模型可能第 5 句才问关键追问，前 4 句都是铺垫）。更好的做法是在 system prompt 里约束长度，`optimizeForVoice` 只作**兜底**而非主手段——**这个分工要讲清楚**。

**(5) system prompt 的组装**——`VoiceInterviewPromptService.java:18-53`。`VOICE_RESPONSE_CONSTRAINTS` 六条：①每轮只问 1 个主问题，必要时最多补 1 个短追问；②总长度 2-4 句，避免长段落/列表/Markdown/代码块；③不要重复开场白，不要复述上一轮已问过的完整问题；④若候选人回答过短或含糊，直接追问具体技术细节或给提示引导，不要简单确认后停止；⑤候选人明确要求换题时立即切换新技术方向；⑥语气简洁直接，适配口语对话。拼接顺序：`SKILL_TOOL_INSTRUCTION`（用 `String.format` 拼 `"…command: %s…"` 让模型调 Skill 工具加载角色设定）→ 上述约束 → 若 `resumeText` 非空则 `promptSanitizer.sanitize(resumeText)` + `wrapWithDelimiters("resume", safeResume)` + "你已查阅过候选人简历。首轮仅用一句话说明已查阅，并立即进入首个问题。" → 末尾 `PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION`。

**重要事实（防止讲错）**：`app/src/main/resources/prompts/` 下**没有** `voice-interview/` 子目录，也没有任何语音面试的 `.st` 文件（该目录 14 个 `.st` 全是简历/评估/知识库/日程的）。语音面试官 prompt **全部是 Java 内联字符串**。AGENTS.md 里写的"语音面试角色模板：`prompts/voice-interview/*.st`"与当前代码不符——**面试时不要说错**。

**出问题会怎样**：LLM 报错时 `mapLlmErrorToUserMessage`（`:181-197`）把技术异常转人话——含 `"403"/"ACCESS_DENIED"/"Authentication"` → "AI 服务认证失败，请检查 API Key 配置"；`"timeout"` → "AI 服务响应超时，请稍后重试"；`"429"/"rate limit"/"quota"` → "AI 服务调用频率超限"；`"connection"/"network"` → "AI 服务网络连接失败"；兜底"抱歉，AI 服务暂时不可用，请稍后重试"。**注意这里不重试**（`application.yml:77-80` 的 `spring.ai.retry.max-attempts: 1`）——重试交给用户再问一次。模型返回空时 `optimizeForVoice` 返回 `"请继续。"`，至少让对话不僵死。

---

#### 环节五：合成与播放——句子级并发 TTS、Semaphore=3、8 秒超时与前端看门狗

**这一步在干什么**：把面试官文本变成声音，并在浏览器里**连续、不卡顿**地播出来。

**涉及的技术**：**句子级流水线**（LLM 还在生成后半句，前半句已在合成，首句音频到达时间从"整段生成完+整段合成完"降到"首句生成完+首句合成完"）；**乱序完成、有序消费**（多句同时合成抢时间，但必须按序播，用 `Map<Integer,CompletableFuture<byte[]>>` + 索引游标）；**Semaphore 限流**；**WAV 头**（TTS 返回裸 PCM，浏览器播不了，要手包 44 字节头）；**看门狗**（watchdog = "X 时间后还没正常结束就强制结束"的定时器，防播放事件丢失导致 UI 永久卡在"面试官正在说话"）；**`AudioContext` vs `HTMLAudioElement`**（前者能做 PCM 级拼接与无缝排队，后者只能整段播）。

**(1) TTS 配置**——`application.yml:243-254`：`model: qwen3-tts-flash-realtime`、`voice: Cherry`、`format: pcm`、`sample-rate: 24000`、`mode: commit`、`language-type: Chinese`、`speech-rate: 1.0`、`volume: 60`。**容易说错的点**：`application.yml:224-226` 另有一个 `tts.default-voice: ${...:siyue}`（注释"思悦 - 温柔自然的女声"），但**实时链路用的是 `qwen.tts.voice: Cherry`，不是 `siyue`**。

**(2) 每次合成都新建一条 WebSocket**——`QwenTtsService.java:115-230`（关键片段）：

```java
CountDownLatch synthesisLatch = new CountDownLatch(1);
ByteArrayContainer audioContainer = new ByteArrayContainer();
QwenTtsRealtime qwenTtsRealtime = new QwenTtsRealtime(param, new QwenTtsRealtimeCallback() {
    @Override public void onEvent(JsonObject m) { handleServerEvent(m, audioContainer, synthesisLatch, errorRef); }
    @Override public void onClose(int code, String reason) { synthesisLatch.countDown(); }
});
try {
    qwenTtsRealtime.connect();                    // 阻塞
    qwenTtsRealtime.updateSession(QwenTtsRealtimeConfig.builder()
        .voice(voice).responseFormat(getAudioFormat()).mode(mode)
        .languageType(languageType).speechRate(speechRate).volume(volume).build());
    qwenTtsRealtime.appendText(text);
    qwenTtsRealtime.commit();                    // 一次性提交整句
    if (!synthesisLatch.await(30, TimeUnit.SECONDS)) {
        log.error("TTS synthesis timeout after 30 seconds");
        return new byte[0];
    }
} finally {
    qwenTtsRealtime.close();                     // 每次合成后关闭
}
```

- `mode: commit` + `appendText` + `commit()`：**攒够文本再一次性合成**，比流式 append 更适合"整句合成"——模型能看完整句再决定语调，韵律更自然。`getAudioFormat()` 固定返回 `QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT`（`:238-241`），硬编码而不读配置，因为 `convertPcmToWav` 里也硬编码 24000。
- **`await(30, SECONDS)` 是服务内部超时，不是链路级的 8 秒**（`tts-timeout-seconds: 8`）。8 秒是上层 `OrderedTtsChunkEmitter` 对单个 chunk 的等待上限，上层先触发（跳过该句），30 秒是最后保险（防调用方永远阻塞）。
- **每条连接用完就 close**：类注释（`:242-249`）写 "each synthesis creates its own temporary connection"。**代价**：每句付一次 TCP+TLS+WS 握手 + `updateSession` 往返，约 100~300ms 固定开销。**好处**：句与句完全隔离，一句失败不污染下一句；也不会因长连接被服务端按空闲超时断开而产生"第 N 句开始没声音"。**这是一条必须能辩护的取舍——面试官很可能问"为什么不复用连接"。**
- `ByteArrayContainer`（`:355-365`）内部 `ByteArrayOutputStream`，`synchronized` 的 append/toByteArray，注释写明"摊还 O(1) 追加，避免 O(n²) 拷贝"——若每收一块就 `Arrays.copyOf(旧数组 + 新块)`，n 块就是 O(n²)。**这个细节值得单独讲。**

**(3) 并发控制核心：`Semaphore(3)` + 顺序下发**——`VoiceInterviewWebSocketHandler.java:976-1091`（内部类 `OrderedTtsChunkEmitter`）：

```java
// triggerLlmResponse:651-652
Semaphore ttsSemaphore = new Semaphore(Math.max(1, voiceInterviewProperties.getMaxConcurrentTtsPerSession()));
int ttsTimeoutSec = Math.max(5, voiceInterviewProperties.getTtsTimeoutSeconds());   // 8

public void submit(String sentence) {
    int index = nextIndex.getAndIncrement();
    ttsSemaphore.acquireUninterruptibly();          // 许可不足就在这里等
    try {
        CompletableFuture<byte[]> f = CompletableFuture.supplyAsync(() -> {
            try { return ttsService.synthesize(sentence); }
            finally { ttsSemaphore.release(); }     // 合成结束才还许可
        }, voicePipelineExecutor);
        synchronized (lock) { futures.put(index, f); lock.notifyAll(); }
    } catch (RuntimeException e) { ttsSemaphore.release(); throw e; }
}
```

- **`Semaphore = 3` 的三个理由**：① **不撞 DashScope 配额**——每次合成新建一条 WS 连接，一句一轮最多 2~4 句，不限并发一个用户就能瞬间开 4 条，10 个并发面试就是 40 条，百炼对同账号实时 TTS 并发连接有配额，超了返回错误，症状是"句子随机没声音"，3 是"够用 + 不撞配额"的保守值；② **不让本地虚拟线程无限增长**——虽然虚拟线程便宜，但每条都持有 socket + 一段 PCM 缓冲；③ **保证前置提前量**——顺序性由 `drainChunks` 保证，并发度只影响提前量，3 已能让第 2、3 句在前一句还没播完时就绪。
- `acquireUninterruptibly`（而非 `acquire`）**故意不响应中断**：若这里抛 `InterruptedException` 中途退出，会导致许可泄漏（拿了一半没 release），最终所有 TTS 卡死。用不可中断版 + `finally release`，保证许可守恒。
- `voicePipelineExecutor` 就是那个虚拟线程池（`:75`），与 LLM 调用共用。**共用而非独立池的理由**：一轮对话里 LLM 与 TTS 串行依赖（先有文本才有 TTS），峰值不同时出现，共用不会互相饿死。

**(4) 乱序完成、有序下发**——`VoiceInterviewWebSocketHandler.java:1010-1091` 的 `drainChunks()`：

```java
private int drainChunks() {
    int index = 0;
    while (true) {
        if (totalChunks >= 0 && index >= totalChunks) { sendAudioComplete(session); return emittedChunks.get(); }
        CompletableFuture<byte[]> f = waitForFuture(index);       // 轮询 lock.wait(100)
        if (f == null) continue;
        try {
            byte[] pcm = f.get(ttsTimeoutSec, TimeUnit.SECONDS);  // 8 秒
            sendAudioChunk(session, convertPcmToWav(pcm), index, false);
            emittedChunks.incrementAndGet();
        } catch (TimeoutException e) {
            log.warn("TTS chunk {} timeout, skipping", index);    // 跳过，不阻塞后续
        } catch (Exception e) {
            log.error("TTS chunk {} failed", index, e);           // 同上
        }
        index++;
    }
}
```

- **永远从 `index = 0` 起顺序取**。第 3 句哪怕先合成完，也必须等第 1、2 句都发出去才轮到它。
- **`lock.wait(100)` 轮询**：`waitForFuture(index)` 在 `futures` 里找不到就睡 100ms 再看。**为什么不用 `CompletableFuture` 链式回调**：链式回调整理乱序输出很别扭（要维护"下一个该发谁"的状态机），轮询简单且开销可忽略。
- **超时/失败就跳过该句并继续下一句**——这是整个设计最关键的决定。**后面的句子不会因为前面一句卡住而永远不播**。代价是用户会听到面试官漏了一句话，但比"卡死不动"好得多。
- `finish()` 由 LLM 流结束调用，设 `totalChunks = nextIndex.get()`，`drainChunks` 才知道"没更多句子了，可以发 `audio_complete`"。`awaitCompletion()` 的超时是 `Math.max(ttsTimeoutSec + 2, (ttsTimeoutSec + 1) * Math.max(1, nextIndex.get()))` 秒，即"10 秒，或 9 × 句数 秒，取大"——**按句子数量动态放宽总超时**，因为 5 句话最坏理论上要 5×8 秒。

**(5) 整段兜底路径**——`VoiceInterviewWebSocketHandler.java:764-790`：

```java
int emitted = chunkEmitter.finish().join();
recordTimer("app.voice.interview.tts.duration", ...);
if (emitted == 0) {                                    // 一句都没合成出来 → 回退整段
    byte[] pcm = ttsService.synthesize(aiReply);
    sendAudio(session, convertPcmToWav(pcm), aiReply);
}
if (emitted == 0 && pcmEmpty) {
    registry.counter("app.voice.interview.tts.empty_audio").increment();
}
```

**兜底的价值**：分句路径依赖"LLM 输出里有终止标点"（`hasTerminalSince`）。如果模型一次吐出一大段没有一个 `。！？；`，`onSentence` 一次都不会被调用 → `emitted == 0` → 用户听不到任何声音。整段兜底保证**任何情况下至少有声音**，`tts.empty_audio` counter 就是给这种情况留的观测点。

**(6) PCM → WAV：手写 44 字节头**——`VoiceInterviewWebSocketHandler.java:1301-1357`：

```java
private byte[] convertPcmToWav(byte[] pcmData) {
    int sampleRate = 24000;      // 硬编码，与 QwenTtsService.getAudioFormat() 对齐
    int bitsPerSample = 16, numChannels = 1;
    int dataSize = pcmData.length;
    int fileSize = dataSize + 36;                     // 44 字节头 - 8 字节 RIFF/WAVE 标识
    byte[] wavData = new byte[44 + dataSize];
    writeString(wavData, 0, "RIFF");   writeIntLE(wavData, 4, fileSize);
    writeString(wavData, 8, "WAVE");   writeString(wavData, 12, "fmt ");
    writeIntLE(wavData, 16, 16);                      // fmt chunk 长度
    writeShortLE(wavData, 20, (short) 1);             // 格式 1 = PCM
    writeShortLE(wavData, 22, (short) numChannels);
    writeIntLE(wavData, 24, sampleRate);
    writeIntLE(wavData, 28, sampleRate * numChannels * bitsPerSample / 8);  // byteRate = 48000
    writeShortLE(wavData, 32, (short) (numChannels * bitsPerSample / 8));   // blockAlign = 2
    writeShortLE(wavData, 34, (short) bitsPerSample);
    writeString(wavData, 36, "data");  writeIntLE(wavData, 40, dataSize);
    System.arraycopy(pcmData, 0, wavData, 44, pcmData.length);
    return wavData;
}
```

- **字节偏移是 WAV 规范固定的**：0-3 `RIFF`、4-7 文件大小-8、8-11 `WAVE`、12-15 `fmt `、16-19 fmt 长度、20-21 格式、22-23 声道、24-27 采样率、**28-31 byteRate**、32-33 blockAlign、34-35 位深、36-39 `data`、**40-43 数据长度**、44 起裸 PCM。
- **`fileSize = dataSize + 36` 不是 +44**：RIFF 头里的大小字段是"从第 4 字节算起的总长"，即 44 - 8 = 36 + 音频数据。
- **byteRate = 24000×1×2 = 48000 字节/秒，这个值前端要用来算播放时长**——两边约定必须一致，**这就是 24kHz 在前端 `AudioContext({sampleRate: 24000})`（`VoiceInterviewPage.tsx:168-173`）和 chunk 解码 `ctx.createBuffer(1, n, 24000)`（`:238`）里出现两次的原因**。
- `writeIntLE` / `writeShortLE`（`:1344-1357`）：WAV 是**小端**，Java 的 `DataOutputStream` 默认大端，所以手写字节。

**(7) 前端播放看门狗：从 WAV 头算时长 + 1.5 秒**——`frontend/src/pages/VoiceInterviewPage.tsx:134-165`（调用点 `:386-402`）：

```tsx
const estimateWavDurationMs = useCallback((base64Wav: string) => {
  try {
    const binary = atob(base64Wav.slice(0, 128));   // 只解前 128 字节，不解整段音频
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    if (bytes.length < 44) return 15_000;           // 头都不完整 → 默认 15 秒
    const view = new DataView(bytes.buffer);
    const byteRate = view.getUint32(28, true);      // WAV 偏移 28，小端
    const dataSize = view.getUint32(40, true);      // WAV 偏移 40，小端
    if (byteRate <= 0 || dataSize <= 0) return 15_000;
    return Math.ceil((dataSize / byteRate) * 1000);
  } catch { return 15_000; }
}, []);
```
```tsx
const durationMs = estimateWavDurationMs(audioData);
audioPlaybackWatchdogRef.current = setTimeout(
  finishAiPlayback,
  Math.min(Math.max(durationMs + 1500, 4000), 60_000)
);
```

- **`base64Wav.slice(0, 128)`**：WAV 头只有 44 字节，base64 后前 128 字符足够覆盖。**不用 `atob` 整个音频**——30 秒 24kHz 音频 base64 后超 1MB，解一次纯浪费。`byteRate`/`dataSize` 的偏移必须和 Java 端写入位置（28/40）一致。**前后端共享一份"WAV 格式约定"是这里最脆的地方**，任何一边改采样率就错位。
- **`+ 1500`**：给播放留 1.5 秒余量。`setAiAudio()` 后要等 React 重渲染 → `useEffect` → `audioPlayerRef.play()`（`:309-319`），中间几十到几百毫秒，再加解码启动时间。**保守方向是对的**——看门狗太早触发会在声音还在播时就把 `isAiSpeaking` 置 false，麦克风提前打开，直接把面试官的话录进去（回声）。
- **下限 4000ms**：极短音频（如"嗯。"）算出可能只有 300ms，需要下限防误触发。**上限 60_000ms**：`ai-question-max-chars: 120` 决定正常音频不超 30 秒，60 秒的顶是"真出异常时最多卡 1 分钟就恢复"。
- **看门狗存在的意义**：`<audio onEnded>`（`:928-930`）只在**正常**播完时触发，但标签切后台（部分浏览器暂停解码）、播放 promise 被 catch、音频源边界条件解码失败这几种情况它**不会触发**。此时若无看门狗，`isAiSpeaking` 永远是 true → `canRecord`（`:715`）永远 false → **用户永远无法再说话，只能刷新页面**。**这就是看门狗防的失效模式，必须讲清楚。**
- 另两个定时器：无音频只有文本时（`:404-417`）`setTimeout(..., 2500)`；`onTextResponse` 的 final 分支（`:419-441`）`15000`。三个定时器对应三种"文本与音频到达顺序不同"的组合。

**(8) chunk 播放：串行接力**——`VoiceInterviewPage.tsx:175-195`：

```tsx
const playNextChunk = useCallback(() => {
  if (chunkQueueRef.current.length === 0) { isChunkPlayingRef.current = false; return; }
  isChunkPlayingRef.current = true;
  const ctx = getAudioContext();
  if (ctx.state === 'suspended') ctx.resume();        // 浏览器自动播放策略
  const buffer = chunkQueueRef.current.shift()!;
  const source = ctx.createBufferSource();
  source.buffer = buffer;
  source.connect(ctx.destination);
  chunkPlaybackSourceRef.current = source;
  source.onended = () => {                            // 播完这块，立刻取下一块
    chunkPlaybackSourceRef.current = null;
    playNextChunk();
  };
  source.start(0);
}, [getAudioContext]);
```

- **`onended` 接力而非 `source.start(nextStartTime)` 预排期**：预排期要在第一个 chunk 到达前就知道所有 chunk 时长（做不到，chunk 流式到达的）。接力有块间**极小间隙**（几十毫秒 JS 回调延迟），但实现简单且能应对"下一块还没到"。`ctx.state === 'suspended'` 时 `resume()`：浏览器自动播放策略要求首次播放由用户手势触发。
- `isChunkPlayingRef` 双重作用：既是"当前有没有在播"的标志（`handleAudioChunk:242-244` 用它决定是否启动接力），也是"队列排空"的判断依据（`scheduleChunkDrainCompletion:204`）。卸载时 `chunkPlaybackSourceRef.current?.stop()`（`:278`）——**正在播的音频必须显式停**，否则组件销毁了声音还在响。

**(9) 排空检测：为什么还要 100ms 轮询**——`VoiceInterviewPage.tsx:197-219`：`setInterval(..., 100)`，队列空且 `!isChunkPlayingRef.current` 就 `clearInterval` + `setAiSpeaking(false)` + `setIsSubmitting(false)` + `commitAiMessage(aiTextRef.current.trim())`；`Date.now() - startedAt > maxDrainWaitMs`（`30_000`）也强制收尾。

- **触发时机**：收到 `audio_complete`（服务端所有 chunk 发完）或收到 `isLast` 的 chunk。
- **为什么轮询而不是在最后一个 chunk 播完时收尾**：`isLast` 标记的是**服务端发送的最后一个**，不是"队列里最后一个已被播放的"；且服务端超时跳过某句时，`isLast` 的 index 可能和实际收到的最后一块对不上。**轮询"队列空 + 没有正在播"是唯一可靠的判空条件。**
- **30 秒上限**：防某块 `onended` 事件丢失导致轮询永不结束。**这个函数与看门狗是两套独立机制**：chunk 路径用轮询 + 30 秒兜底，整段路径用 `estimateWavDurationMs` 看门狗。**两条路都必须收尾到 `setAiSpeaking(false)`，否则门控解不开。**

**出问题会怎样**：TTS 全超时 → `emitted == 0` 走整段兜底，整段也空则只推文本字幕 + `tts.empty_audio` counter +1；某句超时 → 跳过该句，用户听到漏句但对话继续；`onended` 丢失 → chunk 路径靠 30 秒轮询救、整段路径靠看门狗救；`AudioContext` 被挂起 → `playNextChunk` 里 `ctx.resume()` 兜，整段路径靠 `play().catch()`（`:313-316`）提示"请点击页面任意位置以启用音频播放"；**前端 `isAiSpeaking` 卡 true** → `canRecord`（`:715`）恒 false、用户无法发言，这是整条链路**后果最严重**的单点故障，所以前面堆了两层兜底。

---

#### 环节六：回声防护——四层，每层防的是不同的失效模式

回声是语音面试最容易做崩的地方：**面试官的声音从扬声器出来被麦克风录进去，发给 ASR 识别成"用户在说话"，再触发一轮 LLM**，结果是面试官自己跟自己对话并无限自激。四层防护是**四种不同的失效模式**，不是重复劳动：

| 层 | 位置 | 机制 | 防的失效模式 |
|---|---|---|---|
| 1 | 采集端 | `getUserMedia({ echoCancellation: true })` `AudioRecorder.tsx:148-155` | 物理回灌本身。浏览器用扬声器参考信号做自适应抵消，成本为零 |
| 2 | 前端门控 | `isAiSpeaking \|\| isSubmitting` 时不上行 `VoiceInterviewPage.tsx:640-646` | 省带宽 + 省 ASR 配额。AI 说话期间音频根本不离开浏览器 |
| 3 | 服务端冷却 | `state.isAiSpeakingOrCooldown()` 时丢弃 `VoiceInterviewWebSocketHandler.java:488-491` | **网络延迟导致的"已停止但音频还在路上"** |
| 4 | 服务端丢弃 | `state.isProcessing().get()` 时丢弃迟到的 STT 定稿段 `:557-562` | **ASR 侧的延迟回调** |

**(1) 第 2 层：前端门控**——`VoiceInterviewPage.tsx:639-652`：

```tsx
// 麦克风音频持续发送给服务端做 ASR；仅 AI 播放时停发，避免回声进入识别
const handleAudioData = (audioData: string) => {
  if (isAiSpeakingRef.current || isSubmittingRef.current) return;   // AI 在说/正在等回答 → 不上行
  if (!isAsrReadyRef.current) return;                              // ASR 没就绪 → 不上行
  if (wsRef.current && wsRef.current.isConnected()) {
    wsRef.current.sendAudio(audioData);
  } else {
    setError('未连接到服务器，请刷新页面重试');
  }
};
```

- **为什么用 `isAiSpeakingRef`（ref）而不是 `isAiSpeaking`（state）**：`handleAudioData` 是通过 `AudioRecorder` 的 `onAudioData` prop 传下去的，用 state 的话闭包会捕获旧值（stale closure），"AI 刚开始说话那一刻"的音频还是会漏过去。ref 永远是当前值。**这个坑要单独讲，是 React 面试加分点。**
- `isSubmittingRef` 一起挡：用户点了提交、AI 还没开始说话的那段（LLM 首 token 300~800ms）也是敏感期。**这一条是"防重复发言"而不是防回声**，容易讲混。

**(2) 第 3 层：800ms 冷却**——写入点 `VoiceInterviewWebSocketHandler.java:837-840`，判定点 `:488-491`：

```java
} finally {
    state.aiSpeaking.set(false);
    state.aiSpeakEndAt.set(System.currentTimeMillis() + AI_SPEAK_COOLDOWN_MS);  // 800ms
}
```
```java
if (state != null && state.isAiSpeakingOrCooldown()) {
    // AI 正在说话或处于回声冷却期时，丢弃麦克风输入，防止回声触发 LLM
    return;
}
```

`isAiSpeakingOrCooldown()` = `aiSpeaking.get() || System.currentTimeMillis() < aiSpeakEndAt.get()`。

- **800ms 干什么用的（关键）**：前端已把"AI 播放期间"的音频挡掉，但**前端的 `isAiSpeaking=false` 与服务端的 `aiSpeaking=false` 不是同一时刻**。时序是：服务端发完最后一块 → `finally` 置 false → 前端**还在播剩下的音频**。所以服务端"以为自己说完了"之后，还会继续收到前端上传的、扬声器里正在播的声音。800ms 就是覆盖这个错位窗口。
- 常量定义在 `:84-91`，注释原文"AI 音频播放结束后的冷却期，防止扬声器尾音被麦克风拾取触发 STT"。**为什么不是更长**：冷却期内用户说话会被静默丢弃，而用户听到面试官说完后往往在 0.5~1 秒内就开始回答。800ms 是"够挡尾音 + 不吃掉用户开头"的折中；设 2000ms 会明显出现"我明明接话了但字幕没出来"。**代价**：这是一段**真实的音频丢失窗口**，用户无法感知，也没有任何 UI 提示。

**(3) 第 4 层：迟到数据丢弃**——`VoiceInterviewWebSocketHandler.java:539-570`：

```java
if (isFinalSegment) {
    if (state.isProcessing().get()) {
        log.debug("Discarding late STT final segment during processing for session {}: {}",
            sessionId, recognizedText);
        return;                                    // 第 4 层：正在处理上一轮 → 丢弃
    }
    state.appendFinalSttSegment(recognizedText);
    sendSubtitle(session, state.getMergeBufferPreview(), false);
    return;
}
// partial：只刷实时字幕，不进 mergeBuffer
state.markSttActivity();
sendSubtitle(session, state.getMergeBufferPreviewWithPartial(recognizedText), false);
```

- **这一层防的失效模式很具体**：ASR 是流式的，音频发出去之后识别结果可能**1~2 秒后才回来**。场景：用户说完 → 提交 → 服务端跑 LLM（`isProcessing = true`）→ **此时 ASR 把刚才那句的最终结果又回了一次**（VAD 边界抖动）。不丢的话，这段文本会被 `appendFinalSttSegment` 加进 `mergeBuffer`，**污染下一轮的回答**——用户会发现上一句答案里的半句话跑到了下一轮。
- **注意 partial 不丢**：即使正在处理，partial 也照常刷字幕（它不进 `mergeBuffer`）。**这个区分很精细**：字幕可以抢先显示，但不能污染待提交内容。**代价**：若用户确实在 AI 说话期间说了新内容（比如"我想换个话题"），这句会被丢掉。与第 3 层冷却窗口叠加，形成约 800ms + 处理时长的静默期。

**出问题会怎样**：只做第 1 层时，AEC 对非线性失真、扬声器音量过大、蓝牙耳机延迟都无能为力，症状是"面试官问完，字幕上出现面试官自己的问题"；只做第 2 层时，网络延迟会让前端"以为 AI 说完了"而服务端还在收积压音频；只做第 3 层时，800ms 覆盖不住网络抖动下几秒才到的攒批音频。**四层任缺一层都会出现"面试官自问自答"**——这是被追问"你做了几层防护、每层防什么"时必须答全的点。

---

#### 环节七：容错——断线重连、暂停恢复、开场不重复

**这一步在干什么**：让整场面试在**各种局部故障下都能继续**，而不是让用户刷新页面从头开始。核心技术：**固定间隔重连**、**健康巡检 + 主动重启**（不等出错，定时检查是否 ready）、**幂等开场**（重连/恢复时识别出"已开过场"）、**状态可恢复的暂停**（把"暂停"做成 DB 状态而非前端状态，才能跨会话恢复）。

**(1) 前端重连：3 次 @ 2 秒**——`frontend/src/api/voiceInterview.ts:247-325`：

```ts
private reconnectAttempts = 0;
private maxReconnectAttempts = 3;
private reconnectDelay = 2000;

this.ws.onopen = () => { this.reconnectAttempts = 0; this.handlers.onOpen?.(); };

this.ws.onclose = (event) => {
  this.handlers.onClose?.(event);
  if (!event.wasClean && this.reconnectAttempts < this.maxReconnectAttempts) {
    this.reconnectAttempts++;
    setTimeout(() => this.connect(), this.reconnectDelay);
  }
};
```
```ts
disconnect(): void {
  if (this.ws) {
    this.reconnectAttempts = this.maxReconnectAttempts;   // 不重连
    this.ws.close(1000, 'User disconnected');
    this.ws = null;
  }
}
```

- **最多 3 次 / 2 秒间隔 = 约 6 秒重连窗口**。**为什么最多 3 次**：WebSocket 断开常见原因是网络切换（WiFi↔4G 通常 1~3 秒恢复）或服务端重启（几秒），3 次足够覆盖；再多会掩盖真故障（服务端挂了），用户看到的是"一直转圈"而不是明确报错。
- **`!event.wasClean`**：`wasClean = true` 表示对端发了正常关闭帧（1000），协议错误/网络断开都是 unclean。**只在 unclean 时重连**，避免"用户点结束 → 触发重连 → 面试又开起来"。
- **`disconnect()` 里 `reconnectAttempts = maxReconnectAttempts` 是全类最"脏"但最实用的写法**——用"顶满计数"表达"禁止重连"，不引入额外 `shouldReconnect` 标志位。**代价是语义不直观**，读代码的人容易以为是个 bug。被问起就答"用一个状态表达两个意思，省一个字段但可读性差"。
- **`onopen` 只重置计数，不做任何"重新订阅/补发"**——因为服务端会话状态是完整的（DB 消息都在，`getHistory` 能还原），重连后服务端只是新开一条 ASR 连接。每次连接都会重走 `afterConnectionEstablished`（`:142-172`）：新建 ASR、发 `welcome`、调 `triggerOpeningQuestionIfNeeded`。

**(2) ASR 健康巡检：每 10 秒查一次，最多重启 2 次**——`VoiceInterviewWebSocketHandler.java:429-454`：

```java
private void scheduleAsrReadyCheck(String sessionId, WebSocketSession session, int retryCount) {
    utteranceMergeScheduler.schedule(() -> checkAsrReadyOrRetry(sessionId, session, retryCount),
        ASR_READY_CHECK_DELAY_SECONDS, TimeUnit.SECONDS);          // 10 秒
}

private void checkAsrReadyOrRetry(String sessionId, WebSocketSession session, int retryCount) {
    if (sttService.isReady(sessionId)) return;                      // 就绪，收工
    if (retryCount < MAX_ASR_READY_RETRY) {                         // 2 次
        sendAsrStatus(session, "asr_reconnecting", "语音识别连接较慢，正在自动重连");
        restartDashScopeStt(sessionId, session, retryCount + 1);
        return;
    }
    sendError(session, "语音识别连接准备超时，请检查语音服务配置或稍后重试");
}
```

常量：`MAX_ASR_READY_RETRY = 2`、`ASR_READY_CHECK_DELAY_SECONDS = 10`（`:84-91`）。

- **为什么要有巡检**：ASR 的 WebSocket 可能"连上了但 `updateSession` 没成功"，或"连上了但服务端内部错误导致 `readyLatch` 一直没 countDown"。这种半死状态不抛异常，只能主动探测。**10 秒的理由**：ASR 正常 1~2 秒内就绪，给 10 秒是**非常宽松**的等待——宁可晚一点重连，也不要误判（误判会重启正常连接，重启期间用户说的话全丢）。
- **最多 2 次**：一次成功就 return；2 次都失败说明是配置问题（API Key 错、配额用完），继续重启只是浪费时间，此时发明确文案让用户知道"不是你网络的问题"。
- `restartTranscription`（`QwenAsrService.java:172-214`）实现是"stop + sleep(200ms) + start"，然后**循环 10 次 × 100ms 检查 `newSession.isReady()` 验证重连**，超时打 warn `"ASR reconnection may not be fully ready after 1 second"`。**sleep(200ms) 是必要的**：立刻重连可能撞上服务端连接回收，导致新连接也被拒。

**(3) 音频发送失败的两级处理**——`VoiceInterviewWebSocketHandler.java:476-537`：

```java
} catch (IllegalStateException e) {
    if (isAsrNotReady(e)) {
        log.debug("ASR not ready, dropping audio chunk for session {}", sessionId);
        return;                                        // 丢弃这块，不重连
    }
    if (shouldRecoverAsrConnection(e)) {
        log.warn("ASR connection lost, restarting for session {}", sessionId);
        restartDashScopeStt(sessionId, session, 0);
        for (int i = 0; i < 15; i++) {                 // 重发同一块，最多 15 × 80ms = 1.2s
            try { Thread.sleep(80); sttService.sendAudio(sessionId, audioData); return; }
            catch (IllegalStateException retryEx) { /* 继续重试 */ }
        }
        sendError(session, "语音识别连接中断，请刷新页面后重试");
    }
}
```

判定函数在 `:400-412`：`shouldRecoverAsrConnection(ex)` 匹配 message 含 `"No active session"` 或 `"ASR append failed"`；`isAsrNotReady(ex)` 匹配 `"ASR session not ready"`。

- **两种异常区别对待是设计上很讲究的一处**：`"ASR session not ready"` = 连接正在建，**丢一块无所谓**（才 200ms 音频），重连反而更糟；`"No active session"` / `"ASR append failed"` = 连接真死了，**必须重连并补发这一块**（否则用户那句话的开头少了 200ms）。**`15 × 80ms = 1.2 秒`**：重连要 stop + 200ms sleep + connect + updateSession，约 0.5~1 秒，刚好覆盖。
- **只在重发全失败后才 `sendError`**，文案"语音识别连接中断，请刷新页面后重试"——**明确告诉用户要动手**，不假装没事。

**(4) 开场不重复：两道保险**——`VoiceInterviewWebSocketHandler.java:211-274`：

```java
private void triggerOpeningQuestionIfNeeded(String sessionId, WebSocketSession session) {
    voicePipelineExecutor.execute(() -> {
        try {
            List<String> history = getHistory(sessionId);
            if (!history.isEmpty()) return;            // 保险 1：已有历史（重连/恢复）→ 不重复开场
            VoiceInterviewSessionEntity sessionEntity = interviewService.getSession(sessionId);
            String aiReply = buildOpeningQuestion(sessionEntity);
            saveMessage(sessionId, null, aiReply);     // 先落库
            sendTextMessage(session, aiReply, true);
            byte[] wav = getOpeningWavAudio(aiReply);
            if (wav.length > 0) sendAudio(session, wav, aiReply);
        } catch (Exception e) {
            log.error("Failed to trigger opening question: {}", sessionId, e);
        }
    });
}

@PostConstruct
void warmupOpeningAudioCache() {
    voicePipelineExecutor.execute(() -> {
        try {
            VoiceInterviewProperties.OpeningConfig opening = voiceInterviewProperties.getOpening();
            for (String template : opening.getSkillQuestions().values()) preloadOpeningAudio(template);
            preloadOpeningAudio(opening.getAlgorithmQuestion());
            preloadOpeningAudio(opening.getBackendQuestion());
            log.info("Opening audio cache warmed: {} entries", openingAudioCache.size());
        } catch (Exception e) { log.warn("Opening audio cache warmup skipped: {}", e.getMessage()); }
    });
}
```

- **`openingAudioCache`（`ConcurrentHashMap<String, byte[]>`，`:79`）**：把所有可能的开场白**在服务启动时就全部合成好**。理由：开场是**用户等待感知最敏感的一刻**——刚点进来若还要等 LLM 生成 + TTS 合成，用户会觉得"卡了"。预热把这 1~3 秒挪到服务启动阶段。
- **`skillQuestions.values()` 有 10 条 + 2 条兜底 = 12 条开场白**（内容在 `app/src/main/resources/voice-interview-opening.yml`，由 `spring.config.import: optional:classpath:voice-interview-opening.yml` 导入，`application.yml:30`）。启动时多花几秒和 12 次 TTS 调用，**换来每个用户第一次听到声音的延迟接近 0**——典型的"用启动时间换首屏体验"。
- **"先落库再推前端"（`saveMessage` 在 `sendTextMessage` 之前）**：注释原文「先落库再推前端，确保用户提交时 DB 中已有该条消息」。原因：`saveMessage` 的 `fillLatestUnansweredQuestion` 依赖"DB 里有一条未配对的 AI 提问"做问答配对（`VoiceInterviewService.java:273-285`），若先推送、用户手快立刻提交，此时 DB 里还没那条 AI 提问，配对就错了。
- **两道保险的分工**：`getHistory(sessionId).isEmpty()` 覆盖**同一会话的重连/恢复**；`openingAudioCache` 覆盖**同一句话不需重复合成**（不解决重复开场，解决的是重复合成）。缓存 miss 时 `getOpeningWavAudio`（`:256-266`）会现场 `synthesizeToWav` 再回填，**启动预热失败也不影响功能**。

**(5) 暂停 / 恢复 / 组件卸载自动暂停**

- **服务端超时暂停**：`@Scheduled(fixedRate = 30000) checkPauseTimeout()`（`:1127-1145`）每 30 秒扫 `lastActivityTime`：`WARNING_TIME_MS = 4.5*60*1000`（4:30）到 `PAUSE_TIMEOUT_MS = 5*60*1000`（5:00）之间发 `pause_timeout_warning`（"会话将在30秒后暂停，请继续说话或点击继续"）；超 5 分钟走 `handlePauseTimeout`（`:1179-1211`）：发 `pause_timeout` → `pauseSession(sessionId, "timeout")` → `close(CloseStatus.GOING_AWAY)` → 停 ASR + 清三个 map。
- **状态落库**：`pauseSession` 置 `status=PAUSED` + `pausedAt=now`（`VoiceInterviewService.java:324-344`，状态不对抛 `BusinessException(BAD_REQUEST, "会话状态为 X，无法暂停")`）；`resumeSession` 要求"必须是 PAUSED 才能恢复"，置回 `IN_PROGRESS` + `resumedAt`（`:353-376`）。**必须落库而不能只在前端记**，因为用户可能关浏览器过一会从"面试记录"页面恢复。
- **组件卸载自动暂停**——`frontend/src/pages/VoiceInterviewPage.tsx:269-291`：清理 timer、`disconnect()`、清看门狗、`chunkPlaybackSourceRef.current?.stop()`、`audioContextRef.current?.close()`、清 drain interval、`clearPendingAiTextCommit()`，最后：

```tsx
// 用户没有主动结束/暂停时，自动暂停 session
const currentSessionId = sessionId;
if (currentSessionId && !endedByUserRef.current) {
  voiceInterviewApi.pauseSession(currentSessionId).catch(() => {});
}
```

- **`endedByUserRef` 是必需的守卫**：没有它，用户点"结束面试"（`handleEndInterview:691-707` 先调 `endSession`）→ 组件卸载 → 又发一次 `pauseSession`，而后端要求状态必须是 `IN_PROGRESS`，此时已是 `COMPLETED`，会抛 `BusinessException(BAD_REQUEST, "会话状态为 COMPLETED，无法暂停")`。
- **`.catch(() => {})` 静默吞掉**：卸载时的清理请求失败不值得打扰用户（页面已经在跳走）。**停 source 必须在关 context 之前**，反过来会报"已关闭的 context 被引用"。
- **服务端对称兜底**——`afterConnectionClosed:366-393`：清三个 map → `removedState.getProcessingThread().interrupt()` → `sttService.stopTranscription` → **`interviewService.endSessionIfInProgress(sessionId)`**（注释原文「WebSocket 异常断开时自动结束会话，防止状态永远停留在 IN_PROGRESS」）。用 `endSessionIfInProgress` 而非 `endSession`，因为它**只处理 IN_PROGRESS**，不会覆盖已 PAUSED / COMPLETED 的会话（`VoiceInterviewService.java:103-112`）。
- **`@Scheduled(fixedRate = 300_000) cleanupStaleSessions()`**（`:1147-1157`）每 5 分钟兜底：`minusHours(2)` 的 IN_PROGRESS 强制结束 + `minusMinutes(30)` 卡在 PROCESSING 的评估置 FAILED（`VoiceInterviewService.java:647-675`）。**防的是"服务端进程被杀，连 `afterConnectionClosed` 都没跑"的场景**。

**出问题会怎样**：前端重连 3 次全失败 → `onClose` 里 `event.code !== 1000` → `setError('连接已断开，请刷新页面重试')`（`:442-449`）；ASR 半死 → 前端一直显示"语音识别准备中"（`isAsrReady=false`），`canRecord` 为 false、麦克风按钮点不动，10 秒后服务端开始重启，最多 2 次；恢复会话时历史有连续两条 AI 消息（异常数据）→ 恢复逻辑（`:576-606`）的 `pendingAi` 会丢掉前一条；**`resumeSession` 后再连时 `triggerOpeningQuestionIfNeeded` 因 `getHistory` 非空而跳过开场**——这是"恢复不重复自我介绍"能工作的唯一原因。

---

#### 短板与改法

##### 短板 1：阶段状态机是死代码，`currentPhase` 只有装饰作用

- **现象**：`VoiceInterviewService.shouldTransitionToNextPhase(session, phaseStartTime, questionCount)`（`VoiceInterviewService.java:440-475`）实现了三条自动推进规则（超最长时长强制推进 / 达到最大题数 / 达到建议时长且满足最小题数），`getNextPhase(session)`（`:484-502`）实现"下一个启用的阶段"。但 grep 全仓 `app/src/main/java`，**这两个方法没有任何生产调用者**，只有 `app/src/test/java/interview/guide/modules/voiceinterview/service/VoiceInterviewServiceTest.java`（L414/430/445/463/687/707/727/747/767/787/840/859）和 `.../integration/VoiceInterviewIntegrationTest.java`（L89/131/188/194）在调。生产路径的阶段切换只能靠客户端发 `start_phase`（`VoiceInterviewWebSocketHandler.java:889-890`），而**前端的 `voiceInterview.ts` 和 `VoiceInterviewPage.tsx` 全文没有发送 `start_phase`**（grep 确认）。
- **为什么**：按时长/题数自动推进需要**每轮结束后重新统计**"当前阶段问了几个问题"，而这个计数只能从 DB 消息里数（`getNextSequenceNum` 用全量 count，`saveMessage` 也没按 phase 分组统计）。当时的取舍应是"先把链路跑通，阶段推进先交给 UI"，结果客户端那个入口一直没实现。
- **怎么改**：① 在 `flushMergedUtteranceToLlm` 提交成功后（`:572-609`）加一次阶段判定——从 DB 取当前 phase 的消息数 + 用 `session.getStartTime()` 或 Redis 里记一个 `phaseStartTime` 算时长；② 命中就调 `interviewService.startPhase(sessionId, getNextPhase(session).name())`，并给前端推一条 `control` 消息让 UI 标签跟着变；③ 把当前阶段、目标、已问题数、剩余配额注入 `generateSystemPromptWithContext`（`VoiceInterviewPromptService.java:34-53`），让 LLM 自己知道"该收尾了"；④ 短期最小改动是把阶段配额直接按"总轮数"配、不做阶段划分——**承认这个功能没做，比留一堆死配置好**。

##### 短板 2：语音侧没有已问题目去重，模型会重复问

- **现象**：`VOICE_RESPONSE_CONSTRAINTS` 第 3 条写了"不要重复开场白，不要复述上一轮已问过的完整问题"（`VoiceInterviewPromptService.java:18-26`），`buildPromptContext`（`:157-179`）也确实把历史拼进了 prompt。但这只是**软约束**，靠模型自觉。语音场景比文本场景更容易重复，因为：① 历史被压成 `"面试官：..."` / `"候选人：..."` 纯文本（丢失结构化 role 字段），模型对"哪句是我说的"识别更弱；② 没有"已问题库"做检索去重；③ `getHistory` 返回全量历史**不做条数截断**，长会话时后面的对话被前面的稀释，模型只记得开场那几个问题——**于是它又把开场问题问一遍**。
- **为什么**：文本面试模块（`modules/interview/`）出题走的是"一次性生成整套题目 + 结构化输出"，天生不会重复；语音模块是"每轮现生成"，没有去重机制。
- **怎么改**：① **加已问问题的结构化记录**——给 `VoiceInterviewMessageEntity` 加 `askedQuestionSummary` 字段，或在 Redis 存 `List<String>`，每次生成后写摘要；② **生成前去重**——在 `buildPromptContext` 里把"已问过的 N 个问题"单独列一块 `【已经问过的问题，不要重复】`，比埋在历史里有效得多；③ **加事后校验**——生成完用一次轻量相似度检查（复述给同一个 LLM 问"是否与以下问题语义重复"），命中就重生成一次，代价是多一次 LLM 调用但只在小概率路径触发；④ **补上历史截断**——`getHistory` 按轮数（如最近 12 轮）截断 + 前面几轮保留摘要，让 token 有上界（顺带修掉 `message-chat-memory-max-messages: 120` 在手写路径下不生效的问题）。

##### 短板 3：nginx 缺 `/ws/` 代理，生产环境 WebSocket 根本建不起来

- **现象**：`frontend/nginx.conf`（31 行）只有 `location /`（SPA 兜底 `try_files $uri $uri/ /index.html`）和 `location /api/`（`proxy_pass http://app:8080;` + 几个 `proxy_*_timeout 300s`）。**全文没有 `location /ws/` 段，也没有 `proxy_set_header Upgrade $http_upgrade;` / `proxy_set_header Connection "upgrade";` 头透传**。同时前端连的 URL 是 `ws://localhost:8080/ws/voice-interview/{id}`（`VoiceInterviewPage.tsx:547`、`:608`），服务端注册路径也是 `/ws/voice-interview/{sessionId}`（`WebSocketConfig.java` 的 `addHandler`），而 `ws://` 和 `localhost:8080` 是**硬编码**的（`VoiceInterviewService.java:529-539` 的 `buildSessionResponse` 里 `String.format("ws://localhost:8080/ws/voice-interview/%d", ...)`）。
- **为什么**：`nginx.conf` 是按"普通 REST + SPA"配的，`/api/` 走 HTTP 代理就够。WebSocket 需要 **HTTP Upgrade 协议切换**，nginx 默认只当普通 HTTP 转发、不带 `Upgrade`/`Connection` 头 → 后端收不到升级请求 → 前端 `new WebSocket()` 后立刻 `onclose`（close code 1006，`wasClean=false`）→ 触发重连 3 次全失败 → 用户看到"连接已断开，请刷新页面重试"。而 `webSocketUrl` 硬编码 `localhost:8080` 意味着**即使 nginx 配了 `/ws/`，部署到域名下也连不上**（浏览器会去连用户自己机器的 8080）。
- **怎么改**：① nginx 加一段：
  ```nginx
  location /ws/ {
    proxy_pass http://app:8080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 3600s;      # WebSocket 是长连接，不能 300s 就断
    proxy_send_timeout 3600s;
  }
  ```
  **`proxy_read_timeout` 必须放大**：`/api/` 那段的 300s 对 WebSocket 就是"5 分钟不说话就断"，而 5 分钟正好是服务端暂停阈值——两边超时要能对上，或干脆设得更大。② 后端 `buildSessionResponse` 不要硬编码 host/协议：改成从配置读（如 `app.voice-interview.ws-base-url`），或直接返回**相对路径** `"/ws/voice-interview/" + id`，让前端自己拼：`const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:'; const wsUrl = \`${proto}//${window.location.host}/ws/voice-interview/${sessionId}\`;`。③ 加一个集成测试/启动自检：用 `WebSocketConfig` 期望的路径探测一次握手，把"部署配置错了"提前到发布前暴露。

##### 短板 4：`mergeBuffer` 去重叠只处理前后缀，不处理中间重叠

- **现象**：`joinSegments`（`VoiceInterviewWebSocketHandler.java:1393-1408`）只有四个分支——`next.startsWith(prev)`、`prev.endsWith(next)`、加空格、加逗号，能挡住"ASR 带上下文重发整句"和"尾部重复"两种最常见情况。**为什么**：真正的去重叠要做最长公共子串对齐（或更通用的 diff），复杂度 O(n·m)，在每次 VAD 回调里跑成本偏高。
- **后果**：若 ASR 复述了**中间**一段（比如用户说 A+B，ASR 先返回 A+B，再返回 B+C），拼出来是 "A+B，B+C"——重复的 B 送给 LLM，模型可能被误导。
- **怎么改**：把 `joinSegments` 升级为"后缀/前缀最长公共子串对齐"，并加阈值（重叠 ≥ 4 个汉字才算，避免误判）。代价是 O(n·m)，但 n、m 都是几十个字量级，实际开销可忽略——**这个改动性价比很高，是面试时展示"不仅会发现问题还会算成本"的好素材**。

##### 短板 5：三组配置项完全没被读取，是误导性死配置

- **现象**（grep `app/src/main/java` 全量确认）：① `user-utterance-debounce-ms` / `min-silence-before-commit-ms` / `min-commit-chars` / `max-wait-for-continuation-ms`（`application.yml:256-263`）**只有字段声明与 getter、零调用方**，是一整套"自动提交"方案的设计残留（设计意图见"轮次判断"一节）；② `app.voice-interview.audio`（`codec: opus`、`sample-rate: 16000`、`bit-rate: 24000`、`channels: 1`、`chunk-duration: 2000`，`application.yml:279-285`）——grep `getAudio()` 只命中 `VoiceInterviewProperties.java:22` 的字段声明本身，**零业务引用**，实际链路是 PCM + 3200 采样分块，与这里写的 opus/2000ms 完全不搭；③ `app.voice-interview.rate-limit`（`max-per-session: 10`、`max-per-ip: 3`、`max-concurrent: 50`，`application.yml:273-277`）**无业务引用**，WebSocket 链路上也没有 `@RateLimit`（该注解是 AOP + HTTP 拦截，对 WS 帧级消息不生效）。
- **为什么**：三块都是"先设计配置再实现"的顺序，实现时改了方案（手动提交 / PCM 而非 opus / 没做帧级限流），配置没同步清理。
- **后果**：**面试时照配置文档讲会讲错**——比如讲"我们用了 opus 编码、24kbps 码率"，而实际是 raw PCM 16kHz。这也是旧版文档"讲得不细"的一部分原因：配置项看起来很多，实际生效的只有一半。
- **怎么改**：立刻做一次"配置项 → 调用方"对照表（`@ConfigurationProperties` 类每个字段都 grep 一次），把零引用字段与对应 yml 删掉，或打 `@Deprecated` + 注释说明"预留未启用"。**面试时更好的策略是主动说**："配置里有三组是历史残留、当前没生效，实际生效的是这几项"——比被面试官发现配置与实现不符好得多。
