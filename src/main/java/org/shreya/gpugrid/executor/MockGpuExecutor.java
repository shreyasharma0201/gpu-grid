package org.shreya.gpugrid.executor;

import org.shreya.gpugrid.job.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Profile("dev")
public class MockGpuExecutor implements GpuExecutor {

    private static final Logger log = LoggerFactory.getLogger(MockGpuExecutor.class);

    @Value("${gpugrid.mock.job-duration-minutes:2}")
    private int jobDurationMinutes;

    // executionId → current status
    private final Map<String, JobExecutionStatus> statusMap = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(4, r -> {
                Thread t = new Thread(r, "mock-gpu-worker");
                t.setDaemon(true);
                return t;
            });

    @Override
    public String startJob(Job job, int gpuIndex) {
        String executionId = "mock-" + UUID.randomUUID();
        statusMap.put(executionId, JobExecutionStatus.RUNNING);

        log.info("MockGpuExecutor: starting job bookingId={} on gpu-index={} executionId={} duration={}min",
                job.bookingId(), gpuIndex, executionId, jobDurationMinutes);

        // Simulate job running for jobDurationMinutes, then mark COMPLETED
        scheduler.schedule(() -> {
            if (statusMap.get(executionId) == JobExecutionStatus.RUNNING) {
                statusMap.put(executionId, JobExecutionStatus.COMPLETED);
                log.info("MockGpuExecutor: executionId={} completed", executionId);
            }
        }, jobDurationMinutes, TimeUnit.MINUTES);

        return executionId;
    }

    @Override
    public void stopJob(String executionId) {
        log.info("MockGpuExecutor: stopping executionId={}", executionId);
        statusMap.put(executionId, JobExecutionStatus.FAILED);
    }

    @Override
    public JobExecutionStatus getStatus(String executionId) {
        return statusMap.getOrDefault(executionId, JobExecutionStatus.FAILED);
    }

    @Override
    public List<GpuInfo> listAvailableGpus() {
        // Return 4 mock GPUs for dev use
        return List.of(
                new GpuInfo(0, "Mock-A100-0", true),
                new GpuInfo(1, "Mock-A100-1", true),
                new GpuInfo(2, "Mock-A100-2", true),
                new GpuInfo(3, "Mock-A100-3", true)
        );
    }
}
