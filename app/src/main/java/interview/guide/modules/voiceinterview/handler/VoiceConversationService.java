package interview.guide.modules.voiceinterview.handler;

import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语音面试会话的数据访问与上下文重组。
 * <p>
 * 从原 {@link VoiceInterviewWebSocketHandler} 中拆出，封装三类 DB 交互：
 * </p>
 * <ul>
 *   <li>加载会话消息并重组为「面试官 / 候选人」交替的对话历史（供开场判断与 LLM 上下文使用）；</li>
 *   <li>按 sessionId 获取会话实体；</li>
 *   <li>将一轮问答落库。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceConversationService {

    private final VoiceInterviewService interviewService;

    /**
     * Get chat history for session
     * 加载会话历史，重组为「面试官：… / 候选人：…」交替文本。
     * 注意：若 AI 问题之后暂缺用户回答，该问题会作为 pending 挂起，待下一条消息补齐顺序。
     */
    public List<String> getHistory(String sessionId) {
        try {
            List<VoiceInterviewMessageEntity> messages = interviewService.getConversationHistory(sessionId);
            List<String> history = new ArrayList<>();
            String pendingAiQuestion = null;

            for (VoiceInterviewMessageEntity msg : messages) {
                String aiText = VoiceInterviewMessageEntity.trimToNull(msg.getAiGeneratedText());
                String userText = VoiceInterviewMessageEntity.trimToNull(msg.getUserRecognizedText());

                if (pendingAiQuestion != null) {
                    history.add("面试官：" + pendingAiQuestion);
                    pendingAiQuestion = null;
                    if (userText != null) {
                        history.add("候选人：" + userText);
                    }
                    if (aiText != null) {
                        pendingAiQuestion = aiText;
                    }
                    continue;
                }

                if (aiText != null && userText != null) {
                    history.add("面试官：" + aiText);
                    history.add("候选人：" + userText);
                } else if (aiText != null) {
                    pendingAiQuestion = aiText;
                } else if (userText != null) {
                    history.add("候选人：" + userText);
                }
            }
            if (pendingAiQuestion != null) {
                history.add("面试官：" + pendingAiQuestion);
            }

            log.debug("Loaded {} messages from history for session {}", history.size(), sessionId);
            return history;
        } catch (Exception e) {
            log.error("Error loading conversation history for session {}", sessionId, e);
            return new ArrayList<>();
        }
    }

    /**
     * Get session entity from database
     */
    public VoiceInterviewSessionEntity getSessionEntity(String sessionId) {
        try {
            Long sessionIdLong = Long.parseLong(sessionId);
            return interviewService.getSession(sessionIdLong);
        } catch (NumberFormatException e) {
            log.error("Invalid session ID format: {}", sessionId);
            return null;
        }
    }

    /**
     * Save message to database
     */
    public void saveMessage(String sessionId, String userText, String aiText) {
        try {
            interviewService.saveMessage(sessionId, userText, aiText);
            log.debug("Message saved to database for session: {}", sessionId);
        } catch (Exception e) {
            log.error("Error saving message for session {}", sessionId, e);
        }
    }
}
