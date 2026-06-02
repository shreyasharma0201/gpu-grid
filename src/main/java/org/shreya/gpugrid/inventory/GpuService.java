package org.shreya.gpugrid.inventory;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GpuService {

    private final GpuRepository gpuRepository;

    public GpuService(GpuRepository gpuRepository) {
        this.gpuRepository = gpuRepository;
    }

    public Gpu register(String name, String type) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("GPU name must not be blank");
        }
        Gpu gpu = Gpu.create(name, type);
        return gpuRepository.save(gpu);
    }

    public List<Gpu> listAll() {
        return gpuRepository.findAll();
    }

    public Gpu getById(int id) {
        return gpuRepository.findById(id)
                .orElseThrow(() -> new GpuNotFoundException(id));
    }

    public Gpu updateStatus(int id, GpuStatus status) {
        gpuRepository.updateStatus(id, status);
        return getById(id);
    }
}