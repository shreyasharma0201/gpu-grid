package org.shreya.gpugrid.executor;

import org.shreya.gpugrid.job.Job;

import java.util.List;

public interface GpuExecutor {

    /**
     * Start a job on the given GPU index.
     * Returns an execution ID (docker container id, thread id, etc.)
     */
    String startJob(Job job, int gpuIndex);

    /**
     * Stop a running job by its execution ID.
     */
    void stopJob(String executionId);

    /**
     * Poll the current status of a running job.
     */
    JobExecutionStatus getStatus(String executionId);

    /**
     * List all GPUs visible to this executor.
     */
    List<GpuInfo> listAvailableGpus();
}
