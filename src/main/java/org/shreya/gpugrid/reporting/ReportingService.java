package org.shreya.gpugrid.reporting;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReportingService {

    private final JdbcTemplate jdbc;

    /**
     * Configurable via {@code gpugrid.reporting.hourly-rate-usd} in application.properties.
     * Represents the cost of running one GPU for one hour (e.g. cloud on-demand rate).
     * Default: $2.50 / GPU-hour.
     */
    @Value("${gpugrid.reporting.hourly-rate-usd:2.50}")
    private double hourlyRateUsd;

    public ReportingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------------------
    // Utilization
    // -------------------------------------------------------------------------

    /**
     * GPU utilization % over a time range.
     * Returns one row per GPU: { gpuId, gpuName, activeMinutes, totalMinutes, utilizationPct }
     */
    public List<Map<String, Object>> getUtilization(LocalDateTime from, LocalDateTime to) {
        String sql = """
                SELECT
                    g.id                                          AS gpu_id,
                    g.name                                        AS gpu_name,
                    COALESCE(SUM(CASE WHEN ul.state = 'ACTIVE'
                        THEN 1 ELSE 0 END), 0)                   AS active_minutes,
                    EXTRACT(EPOCH FROM (? ::timestamp - ? ::timestamp)) / 60
                                                                  AS total_minutes,
                    ROUND(
                        100.0 * COALESCE(SUM(CASE WHEN ul.state = 'ACTIVE'
                            THEN 1 ELSE 0 END), 0)
                        / NULLIF(COUNT(ul.id), 0)
                    , 2)                                          AS utilization_pct
                FROM gpus g
                LEFT JOIN gpu_utilization_log ul
                    ON ul.gpu_id = g.id
                   AND ul.logged_at BETWEEN ? AND ?
                GROUP BY g.id, g.name
                ORDER BY g.id
                """;
        return jdbc.queryForList(sql, to, from, from, to);
    }

    // -------------------------------------------------------------------------
    // Cost savings (extended)
    // -------------------------------------------------------------------------

    /**
     * Calculates cost savings by comparing actual GPU usage against a full-idle baseline.
     *
     * <p><b>Model:</b>
     * <ul>
     *   <li>Every GPU-minute logged as IDLE represents "wasted" spend at {@code hourlyRateUsd}.</li>
     *   <li>Every GPU-minute logged as ACTIVE represents utilised value — the scheduler has
     *       successfully avoided that GPU sitting idle.</li>
     *   <li>{@code idleGpuHours   = idleMinutes / 60}</li>
     *   <li>{@code activeGpuHours = activeMinutes / 60}</li>
     *   <li>{@code idleCostUsd    = idleGpuHours   × hourlyRateUsd}  (money lost to idleness)</li>
     *   <li>{@code activeCostUsd  = activeGpuHours × hourlyRateUsd}  (money spent on real work)</li>
     *   <li>{@code potentialCostUsd = totalGpuHours × hourlyRateUsd} (if GPUs ran 100% of the
     *       window, all idle)</li>
     *   <li>{@code estimatedSavingsUsd = potentialCostUsd − idleCostUsd}
     *       (savings vs worst-case full-idle scenario)</li>
     * </ul>
     *
     * <p>The hourly rate is configured via {@code gpugrid.reporting.hourly-rate-usd}.
     */
    public Map<String, Object> getCostSavings(LocalDateTime from, LocalDateTime to) {

        // Per-GPU counts (one row per GPU so we can compute per-device breakdown)
        String perGpuSql = """
                SELECT
                    g.id                                                AS gpu_id,
                    g.name                                              AS gpu_name,
                    COUNT(ul.id)                                        AS total_log_entries,
                    SUM(CASE WHEN ul.state = 'ACTIVE' THEN 1 ELSE 0 END) AS active_minutes,
                    SUM(CASE WHEN ul.state = 'IDLE'   THEN 1 ELSE 0 END) AS idle_minutes
                FROM gpus g
                LEFT JOIN gpu_utilization_log ul
                    ON ul.gpu_id = g.id
                   AND ul.logged_at BETWEEN ? AND ?
                GROUP BY g.id, g.name
                ORDER BY g.id
                """;

        List<Map<String, Object>> gpuRows = jdbc.queryForList(perGpuSql, from, to);

        // Aggregate across all GPUs
        long totalLogEntries = 0;
        long totalActiveMinutes = 0;
        long totalIdleMinutes = 0;

        List<Map<String, Object>> perGpuBreakdown = new java.util.ArrayList<>();

        for (Map<String, Object> row : gpuRows) {
            long entries = toLong(row.get("total_log_entries"));
            long active  = toLong(row.get("active_minutes"));
            long idle    = toLong(row.get("idle_minutes"));

            totalLogEntries    += entries;
            totalActiveMinutes += active;
            totalIdleMinutes   += idle;

            double gpuActiveHours  = active / 60.0;
            double gpuIdleHours    = idle   / 60.0;
            double gpuTotalHours   = (active + idle) / 60.0;
            double gpuIdleCost     = round2(gpuIdleHours   * hourlyRateUsd);
            double gpuActiveCost   = round2(gpuActiveHours * hourlyRateUsd);
            double gpuPotentialCost = round2(gpuTotalHours * hourlyRateUsd);
            double gpuSavings      = round2(gpuPotentialCost - gpuIdleCost);

            Map<String, Object> gpuEntry = new LinkedHashMap<>();
            gpuEntry.put("gpuId",            row.get("gpu_id"));
            gpuEntry.put("gpuName",          row.get("gpu_name"));
            gpuEntry.put("activeMinutes",    active);
            gpuEntry.put("idleMinutes",      idle);
            gpuEntry.put("activeHours",      round2(gpuActiveHours));
            gpuEntry.put("idleHours",        round2(gpuIdleHours));
            gpuEntry.put("idleCostUsd",      gpuIdleCost);
            gpuEntry.put("activeCostUsd",    gpuActiveCost);
            gpuEntry.put("savingsUsd",       gpuSavings);
            perGpuBreakdown.add(gpuEntry);
        }

        // Window duration (applies per-GPU if all started simultaneously)
        Duration window = Duration.between(from, to);
        double windowHours = window.toMinutes() / 60.0;

        double totalActiveHours   = totalActiveMinutes / 60.0;
        double totalIdleHours     = totalIdleMinutes   / 60.0;
        double totalIdleCostUsd   = totalIdleHours   * hourlyRateUsd;
        double totalActiveCostUsd = totalActiveHours * hourlyRateUsd;

        // Potential cost: if every tracked GPU-minute were idle
        double totalTrackedHours  = (totalActiveMinutes + totalIdleMinutes) / 60.0;
        double potentialCostUsd   = totalTrackedHours * hourlyRateUsd;

        // Savings = value delivered by keeping GPUs active vs full-idle baseline
        double estimatedSavingsUsd = potentialCostUsd - totalIdleCostUsd;
//        double estimatedSavingsUsd = totalIdleCostUsd;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from",                  from.toString());
        result.put("to",                    to.toString());
        result.put("windowHours",           round2(windowHours));
        result.put("hourlyRateUsd",         hourlyRateUsd);
        result.put("totalLogEntries",       totalLogEntries);
        result.put("totalActiveMinutes",    totalActiveMinutes);
        result.put("totalIdleMinutes",      totalIdleMinutes);
        result.put("totalActiveHours",      round2(totalActiveHours));
        result.put("totalIdleHours",        round2(totalIdleHours));
        result.put("idleCostUsd",           round2(totalIdleCostUsd));
        result.put("activeCostUsd",         round2(totalActiveCostUsd));
        result.put("potentialCostUsd",      round2(potentialCostUsd));
        result.put("estimatedSavingsUsd",   round2(estimatedSavingsUsd));
        result.put("perGpu",                perGpuBreakdown);
        return result;
    }

    // -------------------------------------------------------------------------
    // Queue depth
    // -------------------------------------------------------------------------

    /**
     * Current queue depth per GPU: how many PENDING bookings are waiting.
     */
    public List<Map<String, Object>> getQueueDepth() {
        String sql = """
                SELECT
                    g.id    AS gpu_id,
                    g.name  AS gpu_name,
                    g.status AS gpu_status,
                    COUNT(b.id) AS pending_bookings
                FROM gpus g
                LEFT JOIN bookings b
                    ON b.gpu_id = g.id
                   AND b.status = 'PENDING'
                GROUP BY g.id, g.name, g.status
                ORDER BY g.id
                """;
        return jdbc.queryForList(sql);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number n) return n.longValue();
        return Long.parseLong(val.toString());
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}