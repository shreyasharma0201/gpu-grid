package org.shreya.gpugrid.api;

import org.shreya.gpugrid.inventory.GpuService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/gpus")
public class GpuController {

    private final GpuService gpuService;

    public GpuController(GpuService gpuService) {
        this.gpuService = gpuService;
    }

    // POST /api/gpus
    @PostMapping
    public ResponseEntity<GpuResponse> register(@RequestBody GpuRequest request) {
        var gpu = gpuService.register(request.name(), request.type());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(GpuResponse.from(gpu));
    }

    // GET /api/gpus
    @GetMapping
    public ResponseEntity<List<GpuResponse>> listAll() {
        var gpus = gpuService.listAll()
                .stream()
                .map(GpuResponse::from)
                .toList();
        return ResponseEntity.ok(gpus);
    }

    // GET /api/gpus/{id}
    @GetMapping("/{id}")
    public ResponseEntity<GpuResponse> getById(@PathVariable int id) {
        return ResponseEntity.ok(GpuResponse.from(gpuService.getById(id)));
    }
}