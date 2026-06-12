package org.shreya.gpugrid.api;

import org.shreya.gpugrid.booking.Booking;

import java.time.LocalDateTime;

public record BookingResponse(
        Integer id,
        Integer gpuId,
        String userId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String status,
        Integer priority,
        LocalDateTime createdAt
) {
    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.id(),
                booking.gpuId(),
                booking.userId(),
                booking.startTime(),
                booking.endTime(),
                booking.status().name(),
                booking.priority(),
                booking.createdAt()
        );
    }
}
