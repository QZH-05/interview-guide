package interview.guide.modules.voiceinterview.handler;

import interview.guide.modules.voiceinterview.handler.VoiceSessionRegistry.SessionState;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * DashScope ASR（语音识别）连接管理。
 * <p>
 * 从原 {@link VoiceInterviewWebSocketHandler} 中拆出，负责：
 * </p>
 * <ul>
 *   <li>连接启动与就绪检查（含有限次自动重连）；</li>
 *   <li>用户音频帧转发与异常恢复（断线重启 + 重试投递）；</li>
 *   <li>STT 回调处理：实时字幕推送、定稿段合并进 {@link SessionState}。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceAsrConnectionManager {

    private static final int MAX_ASR_READY_RETRY = 2;
    private static final long ASR_READY_CHECK_DELAY_SECONDS = 10;

    private final QwenAsrService sttService;
    private final VoiceSessionRegistry sessionRegistry;
    private final VoiceMessageSender messageSender;
    private final VoiceMetrics metrics;
    private final VoiceExecutors voiceExecutors;

    /** 无会话、append 失败等均可重连 ASR */
    private static boolean shouldRecoverAsrConnection(IllegalStateException ex) {
        String m = ex.getMessage();
        if (m == null) {
            return false;
        }
        return m.contains("No active session") || m.contains("ASR append failed");
    }

    private static boolean isAsrNotReady(IllegalStateException ex) {
        String m = ex.getMessage();
        return m != null && m.contains("ASR session not ready");
    }

    /**
     * 为会话启动 DashScope 实时语音识别，并安排就绪检查。
     */
    public void startTranscription(String sessionId, WebSocketSession session) {
        sttService.startTranscription(
                sessionId,
                text -> handleSttResult(sessionId, text, true),
                text -> handleSttResult(sessionId, text, false),
                () -> messageSender.sendAsrReady(session),
                error -> {
                    log.error("STT error for session {}", sessionId, error);
                    messageSender.sendError(session, "语音识别失败: " + error.getMessage());
                }
        );

        scheduleAsrReadyCheck(sessionId, session, 0);
    }

    private void scheduleAsrReadyCheck(String sessionId, WebSocketSession session, int retryCount) {
        voiceExecutors.getUtteranceMergeScheduler().schedule(
            () -> checkAsrReadyOrRetry(sessionId, session, retryCount),
            ASR_READY_CHECK_DELAY_SECONDS,
            TimeUnit.SECONDS
        );
    }

    private void checkAsrReadyOrRetry(String sessionId, WebSocketSession session, int retryCount) {
        if (session == null || !session.isOpen() || sttService.isReady(sessionId)) {
            return;
        }

        if (retryCount < MAX_ASR_READY_RETRY) {
            int nextRetry = retryCount + 1;
            log.warn("[Session: {}] ASR not ready after {}s, retrying ({}/{})",
                sessionId, ASR_READY_CHECK_DELAY_SECONDS, nextRetry, MAX_ASR_READY_RETRY);
            messageSender.sendAsrStatus(session, "asr_reconnecting", "语音识别连接较慢，正在自动重连");
            restartTranscription(sessionId);
            scheduleAsrReadyCheck(sessionId, session, nextRetry);
            return;
        }

        log.warn("[Session: {}] ASR still not ready after {} retries", sessionId, retryCount);
        messageSender.sendError(session, "语音识别连接准备超时，请检查语音服务配置或稍后重试");
    }

    /**
     * DashScope ASR 断线后重连（回调与首次 start 一致）
     */
    private void restartTranscription(String sessionId) {
        WebSocketSession session = sessionRegistry.getSession(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        sttService.restartTranscription(
                sessionId,
                text -> handleSttResult(sessionId, text, true),
                text -> handleSttResult(sessionId, text, false),
                () -> messageSender.sendAsrReady(session),
                error -> {
                    log.error("STT error for session {}", sessionId, error);
                    messageSender.sendError(session, "语音识别失败: " + error.getMessage());
                }
        );
    }

    /**
     * Handle user audio message
     * Just send audio to STT transcriber (results come via callback)
     */
    public void handleUserAudio(String sessionId, String base64Audio) {
        WebSocketSession session = sessionRegistry.getSession(sessionId);
        if (session == null) {
            log.warn("Session not found: {}", sessionId);
            return;
        }

        // AI 正在说话或处于回声冷却期时，丢弃麦克风输入，防止回声触发 LLM
        SessionState state = sessionRegistry.getState(sessionId);
        if (state != null && state.isAiSpeakingOrCooldown()) {
            return;
        }

        try {
            byte[] audioData = Base64.getDecoder().decode(base64Audio);
            log.debug("Received audio data for session {}, size: {} bytes", sessionId, audioData.length);

            try {
                sttService.sendAudio(sessionId, audioData);
            } catch (IllegalStateException ex) {
                if (isAsrNotReady(ex)) {
                    log.debug("[Session: {}] Dropping audio chunk before ASR ready", sessionId);
                    return;
                } else if (shouldRecoverAsrConnection(ex)) {
                    log.warn("[Session: {}] ASR send failed ({}), restarting DashScope and retrying chunk",
                            sessionId, ex.getMessage() != null ? ex.getMessage() : "unknown");
                    restartTranscription(sessionId);
                    boolean sent = false;
                    for (int i = 0; i < 15; i++) {
                        try {
                            Thread.sleep(80);
                            sttService.sendAudio(sessionId, audioData);
                            sent = true;
                            break;
                        } catch (IllegalStateException retry) {
                            if (isAsrNotReady(retry)) {
                                continue;
                            }
                            if (!shouldRecoverAsrConnection(retry)) {
                                throw retry;
                            }
                        }
                    }
                    if (!sent) {
                        log.error("[Session: {}] ASR still down after restart", sessionId);
                        messageSender.sendError(session, "语音识别连接中断，请刷新页面后重试");
                    }
                } else {
                    throw ex;
                }
            }

        } catch (Exception e) {
            log.error("Error handling user audio for session {}", sessionId, e);
            String errorMessage = getErrorMessage(e);
            messageSender.sendError(session, errorMessage);
        }
    }

    /**
     * Handle STT result from callback (partial = live; final = committed segment for LLM).
     */
    private void handleSttResult(String sessionId, String recognizedText, boolean isFinalSegment) {
        WebSocketSession session = sessionRegistry.getSession(sessionId);
        SessionState state = sessionRegistry.getState(sessionId);

        if (session == null || state == null) {
            log.warn("Session or state not found: {}", sessionId);
            return;
        }

        if (!isFinalSegment) {
            state.markSttActivity();
            messageSender.sendSubtitle(session, state.getMergeBufferPreviewWithPartial(recognizedText), false);
            return;
        }

        // 用户已提交、LLM 正在处理时，丢弃迟到的 STT 定稿段，防止污染下一轮 mergeBuffer
        if (state.isProcessing().get()) {
            log.debug("Discarding late STT final segment during processing for session {}: {}",
                sessionId, recognizedText);
            return;
        }

        log.debug("STT final segment for session {}: {}", sessionId, recognizedText);
        metrics.incrementCounter("app.voice.interview.asr.final_segments", "status", "received");

        // 合并多次 VAD 切段，只更新实时字幕；是否提交给 LLM 由前端手动 submit 控制
        state.appendFinalSttSegment(recognizedText);
        messageSender.sendSubtitle(session, state.getMergeBufferPreview(), false);
    }

    /**
     * Convert exception to user-friendly error message
     */
    private String getErrorMessage(Exception e) {
        Throwable cause = e.getCause();

        // Check for specific Aliyun errors
        if (cause != null) {
            String message = cause.getMessage();
            if (message != null) {
                if (message.contains("403") || message.contains("ACCESS_DENIED")) {
                    return "阿里云语音服务认证失败：AccessKey 无效或已过期。请在 .env 文件中配置正确的 ALIYUN_ACCESS_KEY";
                }
                if (message.contains("timeout") || message.contains("channel inactive")) {
                    return "阿里云语音服务连接超时。请检查网络连接或稍后重试";
                }
            }
        }

        // Default error message
        return "语音处理失败：" + e.getMessage();
    }
}
