package org.shreya.gpugrid.reporting;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class ReportingService {

    private final JdbcTemplate jdbc;

    @Value("${gpugrid.reporting.hourly-rate-usd:2.50}")
    private double hourlyRateUsd;

    public ReportingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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

    /**
     * Estimated cost savings: compares actual idle time vs full-idle baseline.
     * savings = idle_hours_avoided * hourly_rate
     */
    public Map<String, Object> getCostSavings(LocalDateTime from, LocalDateTime to) {
        String sql = """
                SELECT
                    COUNT(*)                                            AS total_log_entries,
                    SUM(CASE WHEN state = 'ACTIVE' THEN 1 ELSE 0 END) AS active_entries,
                    SUM(CASE WHEN state = 'IDLE'   THEN 1 ELSE 0 END) AS idle_entries
                FROM gpu_utilization_log
                WHERE logged_at BETWEEN ? AND ?
                """;

        Map<String, Object> row = jdbc.queryForMap(sql, from, to);

        long total  = toLong(row.get("total_log_entries"));
        long active = toLong(row.get("active_entries"));
        long idle   = toLong(row.get("idle_entries"));

        double activeHours  = active / 60.0;
        double idleHours    = idle   / 60.0;
        double savedUsd     = activeHours * hourlyRateUsd;   // value delivered vs full-idle baseline

        return Map.of(
                "from",             from.toString(),
                "to",               to.toString(),
                "totalLogEntries",  total,
                "activeMinutes",    active,
                "idleMinutes",      idle,
                "activeHours",      Math.round(activeHours  * 100.0) / 100.0,
                "idleHours",        Math.round(idleHours    * 100.0) / 100.0,
                "hourlyRateUsd",    hourlyRateUsd,
                "estimatedSavingsUsd", Math.round(savedUsd  * 100.0) / 100.0
        );
    }

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

    private long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number n) return n.longValue();
        return Long.parseLong(val.toString());
    }
}
