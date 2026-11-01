package interview.guide.modules.voiceinterview.handler;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 语音面试 Micrometer 指标采集的统一封装。
 * <p>
 * 从原 {@link VoiceInterviewWebSocketHandler} 中拆出，集中处理 timer / counter 的采集，
 * 未注入 MeterRegistry 时静默跳过（本地单测或未启用监控时不产生副作用）。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class VoiceMetrics {

    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /**
     * 记录从 startNanos 到当前时刻的耗时（纳秒级起点）。
     */
    public void recordTimerSinceNanos(String metricName, long startNanos, String... tags) {
        MeterRegistry registry = getRegistry();
        if (registry == null) {
            return;
        }
        long elapsed = Math.max(0, System.nanoTime() - startNanos);
        registry.timer(metricName, tags).record(elapsed, TimeUnit.NANOSECONDS);
    }

    /**
     * 记录已算好的毫秒级耗时。
     */
    public void recordTimerMillis(String metricName, long millis, String... tags) {
        MeterRegistry registry = getRegistry();
        if (registry == null) {
            return;
        }
        registry.timer(metricName, tags).record(Math.max(0, millis), TimeUnit.MILLISECONDS);
    }

    /**
     * 累加一个 counter 指标。
     */
    public void incrementCounter(String metricName, String... tags) {
        MeterRegistry registry = getRegistry();
        if (registry == null) {
            return;
        }
        registry.counter(metricName, tags).increment();
    }

    private MeterRegistry getRegistry() {
        return meterRegistryProvider.getIfAvailable();
    }
}
