package org.shreya.gpugrid.executor;

public record GpuInfo(
        int index,       // 0-based GPU index (maps to CUDA_VISIBLE_DEVICES)
        String name,     // e.g. "NVIDIA A100", "Mock-GPU-0"
        boolean available
) {}
