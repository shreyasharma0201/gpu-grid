package org.shreya.gpugrid.api;

import java.time.LocalDateTime;

public record BookingRequest(
        Integer gpuId,
        String userId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Integer priority          // optional — defaults to 0
) {}
