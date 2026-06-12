package org.shreya.gpugrid.booking;

import java.time.LocalDateTime;

public record Booking(
        Integer id,
        Integer gpuId,
        String userId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        BookingStatus status,
        Integer priority,
        LocalDateTime createdAt
) {

    public static Booking create(Integer gpuId, String userId,
                                 LocalDateTime startTime, LocalDateTime endTime,
                                 Integer priority) {
        return new Booking(
                null,
                gpuId,
                userId,
                startTime,
                endTime,
                BookingStatus.PENDING,
                priority == null ? 0 : priority,
                null
        );
    }
}