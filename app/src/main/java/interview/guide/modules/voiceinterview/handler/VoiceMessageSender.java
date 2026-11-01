package interview.guide.modules.voiceinterview.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.voiceinterview.dto.WebSocketSubtitleMessage;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 语音面试 WebSocket 下行消息的统一发送器。
 * <p>
 * 从原 {@link VoiceInterviewWebSocketHandler} 中拆出，集中处理：
 * </p>
 * <ul>
 *   <li>JSON 序列化与基础消息发送（含会话关闭保护）；</li>
 *   <li>业务消息：字幕、文本、音频、错误、ASR 状态、音频分块；</li>
 *   <li>PCM → WAV 转换（浏览器播放用 44 字节 WAV 头）；</li>
 *   <li>流式 TTS 分块发送器 {@link OrderedTtsChunkEmitter}（句子级 TTS 并发 + 按序下发）。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceMessageSender {

    private final ObjectMapper objectMapper;
    private final QwenTtsService ttsService;
    private final VoiceExecutors voiceExecutors;

    /**
     * Serialize object to JSON
     */
    public String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Error serializing JSON", e);
            return "{}";
        }
    }

    /**
     * Send message to WebSocket session
     */
    public void sendMessage(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(message));
                log.debug("Message sent to session: {}", message.substring(0, Math.min(100, message.length())));
            } else {
                log.warn("Session is closed, cannot send message");
            }
        } catch (Exception e) {
            log.error("Error sending message to session", e);
        }
    }

    public void sendTextMessage(WebSocketSession session, String text) {
        sendTextMessage(session, text, false);
    }

    public void sendTextMessage(WebSocketSession session, String text, boolean isFinal) {
        sendMessage(session, toJson(Map.of(
                "type", "text",
                "content", text,
                "final", isFinal
        )));
    }

    public void sendSubtitle(WebSocketSession session, String text, boolean isFinal) {
        WebSocketSubtitleMessage subtitle = WebSocketSubtitleMessage.builder()
                .type("subtitle")
                .text(text)
                .isFinal(isFinal)
                .build();
        sendMessage(session, toJson(subtitle));
    }

    public void sendAudio(WebSocketSession session, byte[] audio, String text) {
        if (!session.isOpen()) {
            return;
        }
        String base64Audio = Base64.getEncoder().encodeToString(audio);
        log.info("Sending audio to frontend - WAV size: {} bytes, Base64 length: {}",
                audio.length, base64Audio.length());
        sendMessage(session, toJson(Map.of(
                "type", "audio",
                "data", base64Audio,
                "text", text
        )));
    }

    public void sendError(WebSocketSession session, String error) {
        sendMessage(session, toJson(Map.of("type", "error", "message", error)));
    }

    public void sendAsrReady(WebSocketSession session) {
        sendAsrStatus(session, "asr_ready", "语音识别已就绪");
    }

    public void sendAsrStatus(WebSocketSession session, String action, String message) {
        if (session == null || !session.isOpen()) {
            return;
        }
        sendMessage(session, toJson(Map.of(
            "type", "control",
            "action", action,
            "message", message,
            "timestamp", System.currentTimeMillis()
        )));
    }

    public void sendAudioChunk(WebSocketSession session, byte[] wavAudio, int index, boolean isLast) {
        if (!session.isOpen()) {
            return;
        }
        String base64Audio = Base64.getEncoder().encodeToString(wavAudio);
        sendMessage(session, toJson(Map.of(
                "type", "audio_chunk",
                "data", base64Audio,
                "index", index,
                "isLast", isLast
        )));
        log.debug("[Session] Sent audio chunk index={}, isLast={}, size={} bytes", index, isLast, wavAudio.length);
    }

    public void sendAudioComplete(WebSocketSession session) {
        if (session == null || !session.isOpen()) {
            return;
        }
        sendMessage(session, toJson(Map.of(
            "type", "control",
            "action", "audio_complete",
            "message", "面试官语音播放完成",
            "timestamp", System.currentTimeMillis()
        )));
    }

    /**
     * 创建流式 TTS 分块发送器（句子级 TTS 并发 + 按序下发）。
     */
    public OrderedTtsChunkEmitter createOrderedTtsChunkEmitter(
            String sessionId, WebSocketSession session, Semaphore ttsSemaphore, long ttsTimeoutSec) {
        return new OrderedTtsChunkEmitter(sessionId, session, ttsSemaphore, ttsTimeoutSec);
    }

    /**
     * Convert PCM audio to WAV format
     * Adds 44-byte WAV header to PCM data for browser playback
     *
     * @param pcmData Raw PCM audio data (24kHz, 16-bit, mono)
     * @return WAV formatted audio data
     */
    public byte[] convertPcmToWav(byte[] pcmData) {
        // Use 24000Hz for Qwen TTS Realtime API
        int sampleRate = 24000;
        int bitsPerSample = 16;
        int numChannels = 1;
        int byteRate = sampleRate * numChannels * bitsPerSample / 8;
        int blockAlign = numChannels * bitsPerSample / 8;
        int dataSize = pcmData.length;
        int fileSize = dataSize + 36;

        byte[] wavData = new byte[dataSize + 44];

        // Write WAV header directly to avoid stream allocation overhead
        int pos = 0;

        // RIFF header
        wavData[pos++] = 'R'; wavData[pos++] = 'I'; wavData[pos++] = 'F'; wavData[pos++] = 'F';
        writeIntLE(wavData, pos, fileSize); pos += 4;
        wavData[pos++] = 'W'; wavData[pos++] = 'A'; wavData[pos++] = 'V'; wavData[pos++] = 'E';

        // fmt chunk
        wavData[pos++] = 'f'; wavData[pos++] = 'm'; wavData[pos++] = 't'; wavData[pos++] = ' ';
        writeIntLE(wavData, pos, 16); pos += 4; // Chunk size
        writeShortLE(wavData, pos, (short) 1); pos += 2; // Audio format (1 = PCM)
        writeShortLE(wavData, pos, (short) numChannels); pos += 2;
        writeIntLE(wavData, pos, sampleRate); pos += 4;
        writeIntLE(wavData, pos, byteRate); pos += 4;
        writeShortLE(wavData, pos, (short) blockAlign); pos += 2;
        writeShortLE(wavData, pos, (short) bitsPerSample); pos += 2;

        // data chunk
        wavData[pos++] = 'd'; wavData[pos++] = 'a'; wavData[pos++] = 't'; wavData[pos++] = 'a';
        writeIntLE(wavData, pos, dataSize); pos += 4;

        // Copy PCM data
        System.arraycopy(pcmData, 0, wavData, 44, pcmData.length);

        return wavData;
    }

    /**
     * Write 32-bit integer in little-endian format
     */
    private static void writeIntLE(byte[] buf, int pos, int value) {
        buf[pos] = (byte) (value & 0xFF);
        buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
        buf[pos + 2] = (byte) ((value >> 16) & 0xFF);
        buf[pos + 3] = (byte) ((value >> 24) & 0xFF);
    }

    /**
     * Write 16-bit short in little-endian format
     */
    private static void writeShortLE(byte[] buf, int pos, short value) {
        buf[pos] = (byte) (value & 0xFF);
        buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
    }

    /**
     * 句子级流式 TTS 分块发送器：LLM 流式输出期间每检测到一个完整句子就启动一次 TTS，
     * 并按句子索引顺序向客户端下发 audio_chunk，保证语音与文本顺序一致。
     */
    public class OrderedTtsChunkEmitter {

        private final String sessionId;
        private final WebSocketSession session;
        private final Semaphore ttsSemaphore;
        private final long ttsTimeoutSec;
        private final Map<Integer, CompletableFuture<byte[]>> futures = new ConcurrentHashMap<>();
        private final AtomicInteger nextIndex = new AtomicInteger();
        private final AtomicInteger emittedChunks = new AtomicInteger();
        private final Object lock = new Object();
        private final CompletableFuture<Integer> completion;
        private volatile int totalChunks = -1;

        OrderedTtsChunkEmitter(
                String sessionId,
                WebSocketSession session,
                Semaphore ttsSemaphore,
                long ttsTimeoutSec) {
            this.sessionId = sessionId;
            this.session = session;
            this.ttsSemaphore = ttsSemaphore;
            this.ttsTimeoutSec = ttsTimeoutSec;
            this.completion = CompletableFuture.supplyAsync(this::drainChunks, voiceExecutors.getVoicePipelineExecutor());
        }

        void submit(String sentence) {
            int index = nextIndex.getAndIncrement();
            ttsSemaphore.acquireUninterruptibly();
            CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return ttsService.synthesize(sentence);
                } finally {
                    ttsSemaphore.release();
                }
            }, voiceExecutors.getVoicePipelineExecutor());

            futures.put(index, future);
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        void finish() {
            synchronized (lock) {
                totalChunks = nextIndex.get();
                lock.notifyAll();
            }
        }

        int awaitCompletion() {
            long timeoutSec = Math.max(ttsTimeoutSec + 2, (ttsTimeoutSec + 1) * Math.max(1, nextIndex.get()));
            try {
                return completion.get(timeoutSec, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[Session: {}] Streaming TTS chunk emitter did not finish cleanly: {}",
                    sessionId, e.getMessage());
                completion.cancel(true);
                int emitted = emittedChunks.get();
                if (emitted > 0) {
                    sendAudioComplete(session);
                }
                return emitted;
            }
        }

        private int drainChunks() {
            int index = 0;
            try {
                while (true) {
                    CompletableFuture<byte[]> future = waitForFuture(index);
                    if (future == null) {
                        int emitted = emittedChunks.get();
                        if (emitted > 0) {
                            sendAudioComplete(session);
                        }
                        return emitted;
                    }

                    try {
                        byte[] pcm = future.get(ttsTimeoutSec, TimeUnit.SECONDS);
                        if (pcm != null && pcm.length > 0 && session.isOpen()) {
                            sendAudioChunk(session, convertPcmToWav(pcm), index, false);
                            emittedChunks.incrementAndGet();
                        }
                    } catch (Exception e) {
                        future.cancel(true);
                        log.warn("[Session: {}] Streaming TTS chunk {} failed: {}",
                            sessionId, index, e.getMessage());
                    } finally {
                        futures.remove(index);
                        index++;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Session: {}] Streaming TTS chunk emitter interrupted", sessionId);
                int emitted = emittedChunks.get();
                if (emitted > 0) {
                    sendAudioComplete(session);
                }
                return emitted;
            }
        }

        private CompletableFuture<byte[]> waitForFuture(int index) throws InterruptedException {
            synchronized (lock) {
                while (!futures.containsKey(index)) {
                    if (totalChunks >= 0 && index >= totalChunks) {
                        return null;
                    }
                    lock.wait(100);
                }
                return futures.get(index);
            }
        }
    }
}
