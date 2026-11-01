package interview.guide.modules.voiceinterview.handler;

import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * 语音面试定时任务：暂停超时检测与过期会话清理。
 * <p>
 * 从原 {@link VoiceInterviewWebSocketHandler} 中拆出。原 Handler 同时承担 WebSocket 协议、
 * ASR、管线与调度职责；本组件将两类 {@link Scheduled} 任务独立成 Bean，职责更清晰，
 * 也便于单独测试。
 * </p>
 * <ul>
 *   <li>每 30 秒检查一次在线会话的最后活动时间：4:30 发警告、5:00 超时暂停；</li>
 *   <li>每 5 分钟清理数据库中滞留的 IN_PROGRESS / PROCESSING 过期会话。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceScheduleService {

    private static final long WARNING_TIME_MS = (long) (4.5 * 60 * 1000);  // 4:30
    private static final long PAUSE_TIMEOUT_MS = 5 * 60 * 1000;            // 5:00

    private final VoiceSessionRegistry sessionRegistry;
    private final VoiceMessageSender messageSender;
    private final QwenAsrService sttService;
    private final VoiceInterviewService interviewService;

    /**
     * Scheduled task to check for pause warnings and timeouts
     * Runs every 30 seconds
     * 定时任务：检查暂停警告和超时
     */
    @Scheduled(fixedRate = 30000)
    public void checkPauseTimeout() {
        long now = System.currentTimeMillis();

        sessionRegistry.getActivitySnapshot().forEach((sessionId, lastTime) -> {
            long elapsed = now - lastTime;

            // Send warning at 4:30
            if (elapsed > WARNING_TIME_MS && elapsed < PAUSE_TIMEOUT_MS) {
                sendPauseWarning(sessionId);
            }
            // Timeout at 5:00
            else if (elapsed >= PAUSE_TIMEOUT_MS) {
                log.warn("Session {} inactive for {} minutes, pausing",
                    sessionId, PAUSE_TIMEOUT_MS / 60000);
                handlePauseTimeout(sessionId);
            }
        });
    }

    @Scheduled(fixedRate = 300_000)
    public void cleanupStaleSessions() {
        try {
            int cleaned = interviewService.cleanupStaleSessions();
            if (cleaned > 0) {
                log.info("Stale session cleanup: {} sessions cleaned", cleaned);
            }
        } catch (Exception e) {
            log.error("Error during stale session cleanup", e);
        }
    }

    /**
     * Send pause warning notification
     * 发送暂停警告通知
     */
    private void sendPauseWarning(String sessionId) {
        WebSocketSession session = sessionRegistry.getSession(sessionId);
        if (session != null && session.isOpen()) {
            messageSender.sendMessage(session, messageSender.toJson(Map.of(
                "type", "control",
                "action", "pause_timeout_warning",
                "message", "会话将在30秒后暂停，请继续说话或点击继续",
                "timestamp", System.currentTimeMillis()
            )));
        }
    }

    /**
     * Handle pause timeout - save state and disconnect
     * 处理暂停超时 - 保存状态并断开连接
     */
    private void handlePauseTimeout(String sessionId) {
        WebSocketSession session = sessionRegistry.getSession(sessionId);

        try {
            if (session != null && session.isOpen()) {
                messageSender.sendMessage(session, messageSender.toJson(Map.of(
                    "type", "control",
                    "action", "pause_timeout",
                    "message", "会话因超时已暂停,可在历史记录中恢复",
                    "timestamp", System.currentTimeMillis()
                )));
            }

            // 2. Save session state to database
            interviewService.pauseSession(sessionId, "timeout");

            // 3. Close WebSocket connection
            if (session != null && session.isOpen()) {
                session.close(CloseStatus.GOING_AWAY);
            }

            // 4. Cleanup - Stop ASR session to prevent resource leak
            sttService.stopTranscription(sessionId);
            sessionRegistry.removeSession(sessionId);
            sessionRegistry.removeState(sessionId);
            sessionRegistry.removeActivity(sessionId);

            log.info("Session {} paused due to timeout", sessionId);

        } catch (Exception e) {
            log.error("Error handling pause timeout for session {}", sessionId, e);
        }
    }
}
