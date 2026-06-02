package org.shreya.gpugrid.inventory;

import java.time.LocalDateTime;

public record Gpu(
        Integer id,
        String name,
        String type,
        GpuStatus status,
        LocalDateTime createdAt
) {

    public static Gpu create(String name, String type) {
        return new Gpu(
                null,
                name,
                type,
                GpuStatus.AVAILABLE,
                null
        );
    }
}
