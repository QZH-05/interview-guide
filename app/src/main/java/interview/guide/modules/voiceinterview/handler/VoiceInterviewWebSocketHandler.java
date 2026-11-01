package interview.guide.modules.voiceinterview.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.voiceinterview.dto.WebSocketControlMessage;
import interview.guide.modules.voiceinterview.handler.VoiceSessionRegistry.SessionState;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

/**
 * WebSocket Handler for Voice Interview
 * 语音面试 WebSocket 处理器（协议路由层）
 * <p>
 * Handles real-time bidirectional audio streaming for voice interviews.
 * Processing pipeline: User Audio → STT → LLM → TTS → AI Audio
 * </p>
 * <p>
 * 本类只保留 WebSocket 协议路由与连接生命周期，具体职责已拆分到：
 * {@link VoiceSessionRegistry}（会话状态）、{@link VoiceAsrConnectionManager}（ASR）、
 * {@link VoicePipelineService}（LLM/TTS 编排）、{@link VoiceMessageSender}（消息发送）、
 * {@link VoiceOpeningQuestionService}（开场）、{@link VoiceScheduleService}（定时任务）。
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewWebSocketHandler extends TextWebSocketHandler {

    private static final int WS_SEND_TIME_LIMIT_MS = 10_000;
    private static final int WS_SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    private final ObjectMapper objectMapper;
    private final QwenAsrService sttService;
    private final VoiceInterviewService interviewService;
    private final VoiceSessionRegistry sessionRegistry;
    private final VoiceAsrConnectionManager asrConnectionManager;
    private final VoicePipelineService pipelineService;
    private final VoiceOpeningQuestionService openingQuestionService;
    private final VoiceMessageSender messageSender;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = extractSessionId(session);

        // Increase message size limits for audio streaming
        // 1 second of PCM audio @ 16kHz, 16-bit = ~32KB raw, ~42KB base64
        // Set limit to 256KB to allow some buffer and multiple messages
        session.setTextMessageSizeLimit(256 * 1024); // 256KB
        session.setBinaryMessageSizeLimit(256 * 1024); // 256KB

        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
            session, WS_SEND_TIME_LIMIT_MS, WS_SEND_BUFFER_LIMIT_BYTES
        );

        sessionRegistry.registerSession(sessionId, safeSession);
        log.info("WebSocket connection established for session: {}", sessionId);

        try {
            asrConnectionManager.startTranscription(sessionId, safeSession);

            // 发送欢迎消息
            messageSender.sendMessage(safeSession, createWelcomeMessage());
            // 自动开场：面试官先说开场语并直接提出第一个问题（仅首次连接、无历史消息时触发）
            openingQuestionService.triggerOpeningQuestionIfNeeded(sessionId, safeSession);
        } catch (Exception e) {
            log.error("Error establishing WebSocket connection for session {}", sessionId, e);
            messageSender.sendError(safeSession, "初始化语音识别失败: " + e.getMessage());
        }
    }

    /**
     * Create welcome message
     */
    private String createWelcomeMessage() {
        return messageSender.toJson(Map.of(
            "type", "control",
            "action", "welcome",
            "message", "连接成功，准备开始语音面试",
            "timestamp", System.currentTimeMillis()
        ));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = extractSessionId(session);

        try {
            JsonNode msg = objectMapper.readTree(message.getPayload());
            String type = msg.get("type").asText();
            int messageSize = message.getPayload().length();
            int messageSizeKB = messageSize / 1024;
            if ("audio".equals(type)) {
                log.trace("[WebSocket] Received audio: sessionId={}, size={}KB", sessionId, messageSizeKB);
            } else {
                log.info("[WebSocket] Received message: sessionId={}, type={}, size={}KB ({} bytes)",
                    sessionId, type, messageSizeKB, messageSize);
            }

            if (messageSizeKB > 200) {
                log.warn("[WebSocket] Large message detected: {}KB", messageSizeKB);
            }

            // Update last activity time for pause timeout detection
            // 更新最后活动时间（用于暂停超时检测）
            sessionRegistry.touchActivity(sessionId);

            switch (type) {
                case "audio":
                    String audioData = msg.has("data") ? msg.get("data").asText() : null;
                    if (audioData != null && !audioData.isEmpty()) {
                        asrConnectionManager.handleUserAudio(sessionId, audioData);
                    } else {
                        log.warn("Received audio message without data");
                    }
                    break;
                case "control":
                    try {
                        handleControl(sessionId, objectMapper.treeToValue(msg, WebSocketControlMessage.class));
                    } catch (Exception e) {
                        log.error("Error handling control message for session {}", sessionId, e);
                        messageSender.sendError(session, "控制消息处理失败: " + e.getMessage());
                    }
                    break;
                default:
                    log.warn("Unknown message type: {} for session {}", type, sessionId);
            }

        } catch (Exception e) {
            log.error("Error handling message for session {}", sessionId, e);
            messageSender.sendError(session, "消息处理失败: " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = extractSessionId(session);
        try {
            sessionRegistry.removeSession(sessionId);
            SessionState removedState = sessionRegistry.removeState(sessionId);
            if (removedState != null) {
                Thread t = removedState.getProcessingThread();
                if (t != null) {
                    t.interrupt();
                }
            }
            sessionRegistry.removeActivity(sessionId);

            // Stop STT transcription
            sttService.stopTranscription(sessionId);
            log.info("WebSocket connection closed for session: {}, status: {}", sessionId, status);

            // WebSocket 异常断开时自动结束会话，防止状态永远停留在 IN_PROGRESS
            try {
                interviewService.endSessionIfInProgress(sessionId);
            } catch (Exception endEx) {
                log.warn("Failed to auto-end session {} after disconnect: {}", sessionId, endEx.getMessage());
            }
        } catch (Exception e) {
            log.error("Error cleaning up session {} after close", sessionId, e);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error for session {}", extractSessionId(session), exception);
    }

    /**
     * Handle control message (end_interview, start_phase)
     */
    private void handleControl(String sessionId, WebSocketControlMessage control) {
        log.info("Control message for session {}: action={}, phase={}",
                sessionId, control.getAction(), control.getPhase());

        switch (control.getAction()) {
            case "submit":
                if (control.getData() != null) {
                    Object textObj = control.getData().get("text");
                    if (textObj instanceof String text && !text.isBlank()) {
                        SessionState state = sessionRegistry.getState(sessionId);
                        if (state != null) {
                            state.setMergeBufferDirectly(text);
                        }
                    }
                }
                pipelineService.flushMergedUtteranceToLlm(sessionId);
                break;
            case "end_interview":
                interviewService.endSession(sessionId);
                break;
            case "start_phase":
                interviewService.startPhase(sessionId, control.getPhase());
                break;
        }
    }

    /**
     * Extract session ID from WebSocket URI path
     * Path format: /ws/voice-interview/{sessionId}
     */
    private String extractSessionId(WebSocketSession session) {
        String path = session.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
