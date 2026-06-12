package org.shreya.gpugrid.reporting;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportingController {

    private final ReportingService reportingService;

    public ReportingController(ReportingService reportingService) {
        this.reportingService = reportingService;
    }

    // GET /api/reports/utilization?from=2025-01-01T00:00:00&to=2025-01-02T00:00:00
    @GetMapping("/utilization")
    public ResponseEntity<List<Map<String, Object>>> utilization(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(reportingService.getUtilization(from, to));
    }

    // GET /api/reports/cost-savings?from=...&to=...
    @GetMapping("/cost-savings")
    public ResponseEntity<Map<String, Object>> costSavings(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(reportingService.getCostSavings(from, to));
    }

    // GET /api/reports/queue-depth
    @GetMapping("/queue-depth")
    public ResponseEntity<List<Map<String, Object>>> queueDepth() {
        return ResponseEntity.ok(reportingService.getQueueDepth());
    }
}
