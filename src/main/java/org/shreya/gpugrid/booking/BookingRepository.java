package org.shreya.gpugrid.booking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class BookingRepository {

    private final JdbcTemplate jdbc;

    public BookingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- RowMapper ---

    private final RowMapper<Booking> rowMapper = (rs, rowNum) -> new Booking(
            rs.getInt("id"),
            rs.getInt("gpu_id"),
            rs.getString("user_id"),
            rs.getTimestamp("start_time").toLocalDateTime(),
            rs.getTimestamp("end_time").toLocalDateTime(),
            BookingStatus.valueOf(rs.getString("status")),
            rs.getInt("priority"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    // --- save ---

    public Booking save(Booking booking) {
        if (booking.id() == null) {
            return insert(booking);
        } else {
            return update(booking);
        }
    }

    private Booking insert(Booking booking) {
        String sql = """
            INSERT INTO bookings (gpu_id, user_id, start_time, end_time, status, priority)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql, new String[]{"id"});
            ps.setInt(1, booking.gpuId());
            ps.setString(2, booking.userId());
            ps.setTimestamp(3, Timestamp.valueOf(booking.startTime()));
            ps.setTimestamp(4, Timestamp.valueOf(booking.endTime()));
            ps.setString(5, booking.status().name());
            ps.setInt(6, booking.priority());
            return ps;
        }, keyHolder);

        int generatedId = keyHolder.getKey().intValue();

        return findById(generatedId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Failed to reload Booking after insert, id=" + generatedId
                        ));
    }

    private Booking update(Booking booking) {
        String sql = """
                UPDATE bookings
                SET gpu_id = ?, user_id = ?, start_time = ?, end_time = ?, status = ?, priority = ?
                WHERE id = ?
                """;

        int affected = jdbc.update(sql,
                booking.gpuId(),
                booking.userId(),
                Timestamp.valueOf(booking.startTime()),
                Timestamp.valueOf(booking.endTime()),
                booking.status().name(),
                booking.priority(),
                booking.id());

        if (affected == 0) {
            throw new BookingNotFoundException(booking.id());
        }

        return findById(booking.id())
                .orElseThrow(() -> new BookingNotFoundException(booking.id()));
    }

    // --- findById ---

    public Optional<Booking> findById(int id) {
        String sql = """
                SELECT id, gpu_id, user_id, start_time, end_time, status, priority, created_at
                FROM bookings
                WHERE id = ?
                """;
        return jdbc.query(sql, rowMapper, id).stream().findFirst();
    }

    // --- findAll (with optional filters) ---

    public List<Booking> findAll() {
        String sql = """
                SELECT id, gpu_id, user_id, start_time, end_time, status, priority, created_at
                FROM bookings
                ORDER BY created_at DESC
                """;
        return jdbc.query(sql, rowMapper);
    }

    public List<Booking> findByUserId(String userId) {
        String sql = """
                SELECT id, gpu_id, user_id, start_time, end_time, status, priority, created_at
                FROM bookings
                WHERE user_id = ?
                ORDER BY created_at DESC
                """;
        return jdbc.query(sql, rowMapper, userId);
    }

    public List<Booking> findByGpuId(int gpuId) {
        String sql = """
                SELECT id, gpu_id, user_id, start_time, end_time, status, priority, created_at
                FROM bookings
                WHERE gpu_id = ?
                ORDER BY created_at DESC
                """;
        return jdbc.query(sql, rowMapper, gpuId);
    }

    public List<Booking> findByStatus(BookingStatus status) {
        String sql = """
                SELECT id, gpu_id, user_id, start_time, end_time, status, priority, created_at
                FROM bookings
                WHERE status = ?
                ORDER BY created_at DESC
                """;
        return jdbc.query(sql, rowMapper, status.name());
    }

    // --- updateStatus ---

    public void updateStatus(int id, BookingStatus status) {
        String sql = "UPDATE bookings SET status = ? WHERE id = ?";
        int affected = jdbc.update(sql, status.name(), id);
        if (affected == 0) {
            throw new BookingNotFoundException(id);
        }
    }

    // --- conflict detection (runs inside a transaction with FOR UPDATE) ---
    public boolean hasConflict(int gpuId, LocalDateTime requestedStart, LocalDateTime requestedEnd, Integer excludeBookingId) {

        jdbc.queryForObject(
                "SELECT id FROM gpus WHERE id = ? FOR UPDATE",
                Integer.class,
                gpuId
        );

        String sql;
        Object[] args;

        if (excludeBookingId != null) {
            sql = """
                    SELECT COUNT(*) FROM bookings
                    WHERE gpu_id = ?
                      AND status IN ('RESERVED', 'RUNNING')
                      AND start_time < ?
                      AND end_time   > ?
                      AND id != ?
                    """;
            args = new Object[]{gpuId,
                    Timestamp.valueOf(requestedEnd),
                    Timestamp.valueOf(requestedStart),
                    excludeBookingId};
        } else {
            sql = """
                    SELECT COUNT(*) FROM bookings
                    WHERE gpu_id = ?
                      AND status IN ('RESERVED', 'RUNNING')
                      AND start_time < ?
                      AND end_time   > ?
                    """;
            args = new Object[]{gpuId,
                    Timestamp.valueOf(requestedEnd),
                    Timestamp.valueOf(requestedStart)};
        }

        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    public void deleteAll() {
        jdbc.update("DELETE FROM bookings");
    }

}
