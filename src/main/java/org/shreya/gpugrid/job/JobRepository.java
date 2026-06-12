package org.shreya.gpugrid.job;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class JobRepository {

    private final JdbcTemplate jdbc;

    public JobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<Job> rowMapper = (rs, rowNum) -> {
        Timestamp startedAt    = rs.getTimestamp("started_at");
        Timestamp completedAt  = rs.getTimestamp("completed_at");
        return new Job(
                rs.getInt("id"),
                rs.getInt("booking_id"),
                rs.getString("container_id"),
                JobStatus.valueOf(rs.getString("status")),
                startedAt   != null ? startedAt.toLocalDateTime()   : null,
                completedAt != null ? completedAt.toLocalDateTime() : null,
                rs.getString("error_message")
        );
    };

    // --- save ---

    public Job save(Job job) {
        return job.id() == null ? insert(job) : update(job);
    }

    private Job insert(Job job) {
        String sql = """
                INSERT INTO jobs (booking_id, container_id, status, started_at, completed_at, error_message)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql, new String[]{"id"});
            ps.setInt(1, job.bookingId());
            ps.setString(2, job.containerId());
            ps.setString(3, job.status().name());
            ps.setTimestamp(4, job.startedAt()    != null ? Timestamp.valueOf(job.startedAt())    : null);
            ps.setTimestamp(5, job.completedAt()  != null ? Timestamp.valueOf(job.completedAt())  : null);
            ps.setString(6, job.errorMessage());
            return ps;
        }, keyHolder);

        int generatedId = Objects.requireNonNull(keyHolder.getKey()).intValue();
        return findById(generatedId)
                .orElseThrow(() -> new IllegalStateException("Failed to reload Job after insert, id=" + generatedId));
    }

    private Job update(Job job) {
        String sql = """
                UPDATE jobs
                SET container_id = ?, status = ?, started_at = ?, completed_at = ?, error_message = ?
                WHERE id = ?
                """;
        jdbc.update(sql,
                job.containerId(),
                job.status().name(),
                job.startedAt()   != null ? Timestamp.valueOf(job.startedAt())   : null,
                job.completedAt() != null ? Timestamp.valueOf(job.completedAt()) : null,
                job.errorMessage(),
                job.id());
        return findById(job.id())
                .orElseThrow(() -> new JobNotFoundException(job.id()));
    }

    // --- finders ---

    public Optional<Job> findById(int id) {
        return jdbc.query(
                "SELECT id, booking_id, container_id, status, started_at, completed_at, error_message FROM jobs WHERE id = ?",
                rowMapper, id
        ).stream().findFirst();
    }

    public Optional<Job> findByBookingId(int bookingId) {
        return jdbc.query(
                "SELECT id, booking_id, container_id, status, started_at, completed_at, error_message FROM jobs WHERE booking_id = ? ORDER BY id DESC LIMIT 1",
                rowMapper, bookingId
        ).stream().findFirst();
    }

    public List<Job> findAll() {
        return jdbc.query(
                "SELECT id, booking_id, container_id, status, started_at, completed_at, error_message FROM jobs ORDER BY id DESC",
                rowMapper
        );
    }

    public List<Job> findByStatus(JobStatus status) {
        return jdbc.query(
                "SELECT id, booking_id, container_id, status, started_at, completed_at, error_message FROM jobs WHERE status = ? ORDER BY id",
                rowMapper, status.name()
        );
    }
}
