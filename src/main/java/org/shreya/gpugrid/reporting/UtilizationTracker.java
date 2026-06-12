package org.shreya.gpugrid.reporting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class UtilizationTracker {

    private final JdbcTemplate jdbc;

    public UtilizationTracker(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void logActive(int gpuId, int jobId) {
        log(gpuId, jobId, "ACTIVE");
    }

    public void logIdle(int gpuId, int jobId) {
        log(gpuId, jobId, "IDLE");
    }

    private void log(int gpuId, int jobId, String state) {
        jdbc.update(
            "INSERT INTO gpu_utilization_log (gpu_id, job_id, state) VALUES (?, ?, ?)",
            gpuId, jobId, state
        );
    }
}
