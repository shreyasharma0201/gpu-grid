package org.shreya.gpugrid.job;

import java.time.LocalDateTime;

public record Job(
        Integer id,
        Integer bookingId,
        String containerId,
        JobStatus status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String errorMessage
) {
    /** Factory: create a new PENDING job for a booking. */
    public static Job createForBooking(int bookingId) {
        return new Job(null, bookingId, null, JobStatus.PENDING, null, null, null);
    }
}
