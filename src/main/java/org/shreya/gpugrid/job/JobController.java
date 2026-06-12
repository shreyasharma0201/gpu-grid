package org.shreya.gpugrid.job;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobRepository jobRepository;

    public JobController(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    // GET /api/jobs
    @GetMapping
    public ResponseEntity<List<JobResponse>> listAll() {
        var jobs = jobRepository.findAll()
                .stream()
                .map(JobResponse::from)
                .toList();
        return ResponseEntity.ok(jobs);
    }

    // GET /api/jobs/{id}
    @GetMapping("/{id}")
    public ResponseEntity<JobResponse> getById(@PathVariable int id) {
        var job = jobRepository.findById(id)
                .orElseThrow(() -> new JobNotFoundException(id));
        return ResponseEntity.ok(JobResponse.from(job));
    }
}
