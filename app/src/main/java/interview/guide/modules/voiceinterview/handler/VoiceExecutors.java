package interview.guide.modules.voiceinterview.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 语音面试管线线程池的统一持有者。
 * <p>
 * 原 {@link VoiceInterviewWebSocketHandler} 自行创建并关闭两个线程池；拆分后由本组件统一持有，
 * 供 {@link VoiceAsrConnectionManager}、{@link VoicePipelineService}、{@link VoiceOpeningQuestionService}
 * 等组件注入使用，避免重复创建线程池，也集中管理关闭逻辑。
 * </p>
 * <ul>
 *   <li>{@code utteranceMergeScheduler}：合并多段 STT 定稿后再触发 LLM 的延迟调度（2 个调度线程）；</li>
 *   <li>{@code voicePipelineExecutor}：LLM / TTS / JDBC 等阻塞工作全部跑在虚拟线程上，避免占满调度线程。</li>
 * </ul>
 */
@Component
@Slf4j
public class VoiceExecutors implements DisposableBean {

    private final ScheduledExecutorService utteranceMergeScheduler = createUtteranceMergeScheduler();

    private final ExecutorService voicePipelineExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private static ScheduledExecutorService createUtteranceMergeScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "voice-utterance-merge");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    /**
     * 合并多段 STT 定稿后再触发 LLM 的延迟调度
     */
    public ScheduledExecutorService getUtteranceMergeScheduler() {
        return utteranceMergeScheduler;
    }

    /**
     * LLM / TTS / JDBC 等阻塞工作的虚拟线程执行器
     */
    public ExecutorService getVoicePipelineExecutor() {
        return voicePipelineExecutor;
    }

    @Override
    public void destroy() {
        voicePipelineExecutor.shutdownNow();
        utteranceMergeScheduler.shutdownNow();
        try {
            if (!voicePipelineExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("voicePipelineExecutor did not terminate within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
