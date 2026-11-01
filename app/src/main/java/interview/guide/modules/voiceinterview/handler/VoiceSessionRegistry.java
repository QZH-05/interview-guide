package interview.guide.modules.voiceinterview.handler;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 语音面试在线会话的注册中心。
 * <p>
 * 从原 {@link VoiceInterviewWebSocketHandler} 中拆出，统一持有三类会话级状态：
 * </p>
 * <ul>
 *   <li>{@code sessions}：sessionId → 已包装的 WebSocketSession；</li>
 *   <li>{@code sessionStates}：sessionId → {@link SessionState}（STT 合并缓冲、AI 播放冷却等）；</li>
 *   <li>{@code lastActivityTime}：sessionId → 最后活动时间（供暂停超时检测）。</li>
 * </ul>
 * <p>
 * 所有 Map 均为 {@link ConcurrentHashMap}，方法提供原子读写，供 ASR 管理、管线编排、
 * 定时任务等组件共享。
 * </p>
 */
@Component
public class VoiceSessionRegistry {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();

    // Activity tracking for pause timeout
    // 活动跟踪（用于暂停超时）
    private final Map<String, Long> lastActivityTime = new ConcurrentHashMap<>();

    // ===== sessions =====

    public void registerSession(String sessionId, WebSocketSession session) {
        sessions.put(sessionId, session);
        sessionStates.put(sessionId, new SessionState());
        touchActivity(sessionId);
    }

    public WebSocketSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public WebSocketSession removeSession(String sessionId) {
        return sessions.remove(sessionId);
    }

    // ===== session states =====

    public SessionState getState(String sessionId) {
        return sessionStates.get(sessionId);
    }

    public SessionState removeState(String sessionId) {
        return sessionStates.remove(sessionId);
    }

    // ===== activity tracking =====

    public void touchActivity(String sessionId) {
        lastActivityTime.put(sessionId, System.currentTimeMillis());
    }

    public Long getLastActivity(String sessionId) {
        return lastActivityTime.get(sessionId);
    }

    public void removeActivity(String sessionId) {
        lastActivityTime.remove(sessionId);
    }

    /**
     * 供暂停超时定时任务遍历使用：返回 (sessionId → lastActivityTime) 的只读快照。
     */
    public Map<String, Long> getActivitySnapshot() {
        return Map.copyOf(lastActivityTime);
    }

    /**
     * Internal class to hold session state
     * 会话级状态机：AI 播放冷却、STT 合并缓冲、LLM 处理中标记等。
     */
    public static class SessionState {
        private final AtomicReference<String> accumulatedText = new AtomicReference<>("");
        private final AtomicBoolean processing = new AtomicBoolean(false);
        /** AI 正在播放 TTS 音频，期间丢弃麦克风回声（包可见：VoicePipelineService 直接读写） */
        final AtomicBoolean aiSpeaking = new AtomicBoolean(false);
        /** AI 音频播放结束后，额外等待这段时间再接受用户音频（ms），防止回声尾音（包可见） */
        final AtomicLong aiSpeakEndAt = new AtomicLong(0);
        /** 多段 STT completed 拼接，防抖后再送 LLM */
        private final AtomicReference<String> mergeBuffer = new AtomicReference<>("");
        /** mergeBuffer 开始计时点，用于”最长等待补充”判定 */
        private final AtomicLong mergeStartedAt = new AtomicLong(0);
        /** 最近一次 STT 活动时间（partial/final） */
        private final AtomicLong lastSttActivityAt = new AtomicLong(System.currentTimeMillis());
        /** 当前正在执行 LLM+TTS 管线的虚拟线程，断连时可中断 */
        private volatile Thread processingThread = null;

        public void appendFinalSttSegment(String segment) {
            String s = segment == null ? "" : segment.trim();
            if (s.isEmpty()) {
                return;
            }
            mergeBuffer.updateAndGet(prev -> {
                if (prev == null || prev.isEmpty()) {
                    mergeStartedAt.set(System.currentTimeMillis());
                    return s;
                }
                return joinSegments(prev, s);
            });
            markSttActivity();
        }

        private static String joinSegments(String previous, String next) {
            String trimmedPrevious = previous.trim();
            String trimmedNext = next.trim();
            if (trimmedNext.equals(trimmedPrevious) || trimmedNext.startsWith(trimmedPrevious)) {
                return trimmedNext;
            }
            if (trimmedPrevious.endsWith(trimmedNext)) {
                return trimmedPrevious;
            }
            if (trimmedPrevious.endsWith("。") || trimmedPrevious.endsWith("！")
                    || trimmedPrevious.endsWith("？") || trimmedPrevious.endsWith(".")
                    || trimmedPrevious.endsWith("!") || trimmedPrevious.endsWith("?")) {
                return trimmedPrevious + " " + trimmedNext;
            }
            return trimmedPrevious + "，" + trimmedNext;
        }

        public String getMergeBufferPreview() {
            String s = mergeBuffer.get();
            return s == null ? "" : s;
        }

        public void setMergeBufferDirectly(String text) {
            String s = text == null ? "" : text.trim();
            if (s.isEmpty()) {
                return;
            }
            mergeBuffer.set(s);
            if (mergeStartedAt.get() == 0) {
                mergeStartedAt.set(System.currentTimeMillis());
            }
        }

        public String getMergeBufferPreviewWithPartial(String partial) {
            String current = partial == null ? "" : partial.trim();
            if (current.isEmpty()) {
                return getMergeBufferPreview();
            }

            String confirmed = getMergeBufferPreview();
            if (confirmed.isBlank()) {
                return current;
            }
            return joinSegments(confirmed, current);
        }

        public String takeMergeBufferAndClear() {
            mergeStartedAt.set(0);
            return mergeBuffer.getAndSet("");
        }

        public void markSttActivity() {
            lastSttActivityAt.set(System.currentTimeMillis());
        }

        public long getMergeStartedAt() {
            long value = mergeStartedAt.get();
            return value > 0 ? value : System.currentTimeMillis();
        }

        public long getLastSttActivityAt() {
            return lastSttActivityAt.get();
        }

        public String getAccumulatedText() {
            return accumulatedText.get();
        }

        public void setAccumulatedText(String text) {
            accumulatedText.set(text);
        }

        public AtomicBoolean isProcessing() {
            return processing;
        }

        public void setProcessingThread(Thread t) {
            this.processingThread = t;
        }

        public Thread getProcessingThread() {
            return processingThread;
        }

        public boolean isAiSpeakingOrCooldown() {
            if (aiSpeaking.get()) {
                return true;
            }
            // AI 播放结束后的冷却期（默认 800ms），防止扬声器尾音被录入
            return System.currentTimeMillis() < aiSpeakEndAt.get();
        }
    }
}
