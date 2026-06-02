package org.shreya.gpugrid.api;

import org.shreya.gpugrid.inventory.Gpu;

import java.time.LocalDateTime;

public record GpuResponse(
        Integer id,
        String name,
        String type,
        String status,
        LocalDateTime createdAt
) {
    public static GpuResponse from(Gpu gpu) {
        return new GpuResponse(
                gpu.id(),
                gpu.name(),
                gpu.type(),
                gpu.status().name(),
                gpu.createdAt()
        );
    }
}