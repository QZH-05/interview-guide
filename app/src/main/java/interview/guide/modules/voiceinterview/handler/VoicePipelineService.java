package interview.guide.modules.voiceinterview.handler;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.handler.VoiceMessageSender.OrderedTtsChunkEmitter;
import interview.guide.modules.voiceinterview.handler.VoiceSessionRegistry.SessionState;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.service.DashscopeLlmService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 语音面试「merge → LLM → TTS → 音频」管线编排。
 * <p>
 * 从原 {@link VoiceInterviewWebSocketHandler} 中拆出。职责：
 * </p>
 * <ul>
 *   <li>将用户累积话语（手动 submit / STT 合并缓冲）提交给 LLM；</li>
 *   <li>流式模式：句子级并发 TTS + 按序分块下发；非流式模式：整段合成下发；</li>
 *   <li>单句 TTS 全部失败时回退整段 TTS，保证面试语音不断档；</li>
 *   <li>全程指标采集（首 token 延迟、LLM/TTS 耗时、轮次完成率）。</li>
 * </ul>
 * <p>
 * 阻塞工作全部在虚拟线程执行器上运行，避免占满调度线程。
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoicePipelineService {

    /** AI 音频播放结束后的冷却期，防止扬声器尾音被麦克风拾取触发 STT */
    private static final long AI_SPEAK_COOLDOWN_MS = 800;

    private final DashscopeLlmService llmService;
    private final QwenTtsService ttsService;
    private final VoiceInterviewProperties voiceInterviewProperties;
    private final VoiceExecutors voiceExecutors;
    private final VoiceSessionRegistry sessionRegistry;
    private final VoiceMessageSender messageSender;
    private final VoiceMetrics metrics;
    private final VoiceConversationService conversationService;

    /**
     * 手动提交：获取 mergeBuffer 中累积的用户文本并触发 LLM 管线。
     */
    public void flushMergedUtteranceToLlm(String sessionId) {
        WebSocketSession session = sessionRegistry.getSession(sessionId);
        SessionState state = sessionRegistry.getState(sessionId);
        if (session == null || state == null || !session.isOpen()) {
            return;
        }
        if (!state.isProcessing().compareAndSet(false, true)) {
            voiceExecutors.getUtteranceMergeScheduler().schedule(
                    () -> flushMergedUtteranceToLlm(sessionId),
                    400,
                    TimeUnit.MILLISECONDS);
            return;
        }
        long mergeStartAt = state.getMergeStartedAt();
        String userText = state.takeMergeBufferAndClear();
        if (userText == null || userText.trim().isEmpty()) {
            state.isProcessing().set(false);
            return;
        }
        long mergeWaitMs = Math.max(0, System.currentTimeMillis() - mergeStartAt);
        metrics.recordTimerMillis("app.voice.interview.asr.merge_wait", mergeWaitMs, "status", "success");
        state.setAccumulatedText(userText);
        log.info("Merged user utterance for session {}, triggering LLM (length {})", sessionId, userText.length());

        // 提交到虚拟线程执行阻塞的 LLM+TTS 管线，立即释放调度器线程
        voiceExecutors.getVoicePipelineExecutor().execute(() -> {
            state.setProcessingThread(Thread.currentThread());
            try {
                triggerLlmResponse(sessionId, session, state);
            } finally {
                state.isProcessing().set(false);
                state.setProcessingThread(null);
            }
        });
    }

    /**
     * Trigger LLM response for completed sentence.
     * When streaming is enabled, uses sentence-level TTS overlap: each detected sentence
     * triggers a concurrent TTS call, so TTS runs in parallel with the rest of LLM generation.
     */
    private void triggerLlmResponse(String sessionId, WebSocketSession session, SessionState state) {
        long turnStartNanos = System.nanoTime();
        state.aiSpeaking.set(true);
        try {
            if (!session.isOpen()) {
                log.warn("WebSocket session is closed, skipping LLM response for session {}", sessionId);
                return;
            }

            String userText = state.getAccumulatedText();
            if (userText == null || userText.trim().isEmpty()) {
                log.warn("Empty user text, skipping LLM response");
                return;
            }

            log.info("Getting LLM response for session {}, text: {}", sessionId, userText);

            VoiceInterviewSessionEntity sessionEntity = conversationService.getSessionEntity(sessionId);
            if (sessionEntity == null) {
                log.error("Session entity not found for session {}, cannot generate LLM response", sessionId);
                messageSender.sendError(session, "会话不存在，请重新开始面试");
                return;
            }

            List<String> conversationHistory = conversationService.getHistory(sessionId);

            long llmStartNanos = System.nanoTime();
            AtomicLong firstTokenAtNanos = new AtomicLong(0);
            boolean streamEnabled = voiceInterviewProperties.isLlmStreamingEnabled();
            String aiReply;

            if (streamEnabled) {
                // 句子级并发 TTS：LLM 流式输出期间每检测到一个完整句子就启动 TTS
                Semaphore ttsSemaphore = new Semaphore(
                    Math.max(1, voiceInterviewProperties.getMaxConcurrentTtsPerSession()));
                boolean chunkedEnabled = voiceInterviewProperties.isChunkedAudioEnabled();
                long ttsTimeoutSec = Math.max(5, voiceInterviewProperties.getTtsTimeoutSeconds());
                OrderedTtsChunkEmitter chunkEmitter = chunkedEnabled
                    ? messageSender.createOrderedTtsChunkEmitter(sessionId, session, ttsSemaphore, ttsTimeoutSec)
                    : null;
                List<CompletableFuture<byte[]>> ttsFutures = new ArrayList<>();

                aiReply = llmService.chatStreamSentences(
                    userText,
                    partialText -> {
                        if (partialText == null || partialText.isBlank() || !session.isOpen()) {
                            return;
                        }
                        if (firstTokenAtNanos.compareAndSet(0L, System.nanoTime())) {
                            metrics.recordTimerSinceNanos(
                                "app.voice.interview.llm.first_token_latency",
                                llmStartNanos,
                                "status", "success"
                            );
                        }
                        messageSender.sendTextMessage(session, partialText, false);
                    },
                    sentence -> {
                        if (sentence == null || sentence.isBlank()) {
                            return;
                        }
                        if (chunkEmitter != null) {
                            chunkEmitter.submit(sentence);
                            return;
                        }
                        ttsSemaphore.acquireUninterruptibly();
                        CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
                            try {
                                return ttsService.synthesize(sentence);
                            } finally {
                                ttsSemaphore.release();
                            }
                        }, voiceExecutors.getVoicePipelineExecutor());
                        ttsFutures.add(future);
                    },
                    sessionEntity,
                    conversationHistory
                );

                metrics.recordTimerSinceNanos("app.voice.interview.llm.duration", llmStartNanos, "status", "success");
                metrics.incrementCounter("app.voice.interview.llm.calls", "status", "success", "streaming", "true");
                log.info("LLM response for session {}: '{}'", sessionId, aiReply);

                if (!session.isOpen()) {
                    log.warn("WebSocket closed during LLM processing, discarding response for session {}", sessionId);
                    return;
                }

                messageSender.sendSubtitle(session, userText, true);
                messageSender.sendTextMessage(session, aiReply, true);
                conversationService.saveMessage(sessionId, userText, aiReply);

                // 按顺序收集所有 TTS 结果（带超时，防止单句 TTS 挂死阻塞整条管道）
                if (chunkEmitter != null) {
                    long ttsStartNanos = System.nanoTime();
                    chunkEmitter.finish();
                    int emittedChunks = chunkEmitter.awaitCompletion();
                    metrics.recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");
                    if (emittedChunks == 0 && session.isOpen()) {
                        log.info("[Session: {}] Streaming TTS produced no chunks, falling back to full-text TTS",
                            sessionId);
                        try {
                            byte[] fallbackPcm = ttsService.synthesize(aiReply);
                            if (fallbackPcm != null && fallbackPcm.length > 0) {
                                messageSender.sendAudio(session, messageSender.convertPcmToWav(fallbackPcm), aiReply);
                            }
                        } catch (Exception e) {
                            log.warn("[Session: {}] Fallback TTS failed: {}", sessionId, e.getMessage());
                        }
                    }
                } else if (!ttsFutures.isEmpty()) {
                    long ttsStartNanos = System.nanoTime();
                    // 合并模式：收集所有 PCM 后合并为一个完整音频
                    List<byte[]> pcmChunks = new ArrayList<>();
                    int totalSize = 0;
                    int failedCount = 0;
                    boolean audioSentByFallback = false;
                    for (CompletableFuture<byte[]> f : ttsFutures) {
                        try {
                            byte[] pcm = f.get(ttsTimeoutSec, TimeUnit.SECONDS);
                            if (pcm != null && pcm.length > 0) {
                                pcmChunks.add(pcm);
                                totalSize += pcm.length;
                            }
                        } catch (Exception e) {
                            f.cancel(true);
                            failedCount++;
                            log.warn("[Session: {}] TTS future failed for one sentence: {}", sessionId, e.getMessage());
                        }
                    }
                    metrics.recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");

                    if (!session.isOpen()) {
                        log.warn("WebSocket closed during TTS processing, discarding audio for session {}", sessionId);
                        return;
                    }

                    // 有句子级 TTS 失败且无成功结果时，用完整文本做一次兜底 TTS
                    if (totalSize == 0 && failedCount > 0 && session.isOpen()) {
                        log.info("[Session: {}] All {} sentence TTS calls failed, falling back to full-text TTS",
                            sessionId, failedCount);
                        try {
                            byte[] fallbackPcm = ttsService.synthesize(aiReply);
                            if (fallbackPcm != null && fallbackPcm.length > 0) {
                                byte[] wavAudio = messageSender.convertPcmToWav(fallbackPcm);
                                log.info("[Session: {}] Fallback TTS succeeded, WAV size: {} bytes",
                                    sessionId, wavAudio.length);
                                messageSender.sendAudio(session, wavAudio, aiReply);
                                audioSentByFallback = true;
                            }
                        } catch (Exception e) {
                            log.warn("[Session: {}] Fallback TTS also failed: {}", sessionId, e.getMessage());
                        }
                    }

                    if (!audioSentByFallback) {
                        if (totalSize > 0 && session.isOpen()) {
                            byte[] mergedPcm = new byte[totalSize];
                            int offset = 0;
                            for (byte[] chunk : pcmChunks) {
                                System.arraycopy(chunk, 0, mergedPcm, offset, chunk.length);
                                offset += chunk.length;
                            }
                            byte[] wavAudio = messageSender.convertPcmToWav(mergedPcm);
                            log.info("[Session: {}] Sending merged audio - {} sentences, WAV size: {} bytes",
                                sessionId, pcmChunks.size(), wavAudio.length);
                            messageSender.sendAudio(session, wavAudio, aiReply);
                        } else {
                            log.error("[Session: {}] All TTS calls returned empty audio", sessionId);
                            metrics.incrementCounter("app.voice.interview.tts.empty_audio", "status", "empty");
                        }
                    }
                }
            } else {
                aiReply = llmService.chat(userText, sessionEntity, conversationHistory);
                metrics.recordTimerSinceNanos("app.voice.interview.llm.duration", llmStartNanos, "status", "success");
                metrics.incrementCounter("app.voice.interview.llm.calls", "status", "success", "streaming", "false");
                log.info("LLM response for session {}: '{}'", sessionId, aiReply);

                if (!session.isOpen()) {
                    log.warn("WebSocket closed during LLM processing, discarding response for session {}", sessionId);
                    return;
                }

                messageSender.sendSubtitle(session, userText, true);
                messageSender.sendTextMessage(session, aiReply, true);
                conversationService.saveMessage(sessionId, userText, aiReply);

                long ttsStartNanos = System.nanoTime();
                log.info("[Session: {}] Starting TTS synthesis for text (length: {})",
                    sessionId, aiReply.length());
                byte[] aiAudio = ttsService.synthesize(aiReply);
                metrics.recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");

                if (!session.isOpen()) {
                    return;
                }

                if (aiAudio == null || aiAudio.length == 0) {
                    log.error("[Session: {}] TTS returned empty audio", sessionId);
                    metrics.incrementCounter("app.voice.interview.tts.empty_audio", "status", "empty");
                } else {
                    byte[] wavAudio = messageSender.convertPcmToWav(aiAudio);
                    messageSender.sendAudio(session, wavAudio, aiReply);
                }
            }

            state.setAccumulatedText("");
            metrics.recordTimerSinceNanos("app.voice.interview.turn.duration", turnStartNanos, "status", "success");
            metrics.incrementCounter("app.voice.interview.turn.completed", "status", "success");

        } catch (Exception e) {
            log.error("Error triggering LLM response for session {}", sessionId, e);
            metrics.recordTimerSinceNanos("app.voice.interview.turn.duration", turnStartNanos, "status", "failure");
            metrics.incrementCounter("app.voice.interview.turn.completed", "status", "failure");
            metrics.incrementCounter("app.voice.interview.errors", "stage", "turn");
            if (session.isOpen()) {
                messageSender.sendError(session, "AI响应失败: " + e.getMessage());
            }
        } finally {
            state.aiSpeaking.set(false);
            state.aiSpeakEndAt.set(System.currentTimeMillis() + AI_SPEAK_COOLDOWN_MS);
        }
    }
}
