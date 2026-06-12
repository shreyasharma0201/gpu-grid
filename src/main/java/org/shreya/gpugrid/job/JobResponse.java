package org.shreya.gpugrid.job;

import java.time.LocalDateTime;

public record JobResponse(
        Integer id,
        Integer bookingId,
        String containerId,
        String status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String errorMessage
) {
    public static JobResponse from(Job job) {
        return new JobResponse(
                job.id(),
                job.bookingId(),
                job.containerId(),
                job.status().name(),
                job.startedAt(),
                job.completedAt(),
                job.errorMessage()
        );
    }
}
