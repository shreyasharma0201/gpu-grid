package org.shreya.gpugrid.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class GpuRepository {

    private final JdbcTemplate jdbc;

    public GpuRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- RowMapper ---

    private final RowMapper<Gpu> rowMapper = (rs, rowNum) -> new Gpu(
            rs.getInt("id"),
            rs.getString("name"),
            rs.getString("type"),
            GpuStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    // --- save ---

    public Gpu save(Gpu gpu) {
        if (gpu.id() == null) {
            return insert(gpu);
        } else {
            return update(gpu);
        }
    }

    private Gpu insert(Gpu gpu) {
        String sql = """
            INSERT INTO gpus (name, type, status)
            VALUES (?, ?, ?)
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    sql,
                    new String[]{"id"}
            );

            ps.setString(1, gpu.name());
            ps.setString(2, gpu.type());
            ps.setString(3, gpu.status().name());

            return ps;
        }, keyHolder);

        int generatedId = keyHolder.getKey().intValue();

        return findById(generatedId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Failed to reload GPU after insert, id=" + generatedId
                        ));
    }

    private Gpu update(Gpu gpu) {
        String sql = """
                UPDATE gpus
                SET name = ?, type = ?, status = ?
                WHERE id = ?
                """;

        int affected = jdbc.update(sql,
                gpu.name(),
                gpu.type(),
                gpu.status().name(),
                gpu.id());

        if (affected == 0) {
            throw new GpuNotFoundException(gpu.id());
        }

        return findById(gpu.id())
                .orElseThrow(() -> new GpuNotFoundException(gpu.id()));
    }

    // --- findAll ---

    public List<Gpu> findAll() {
        String sql = "SELECT id, name, type, status, created_at FROM gpus ORDER BY id";
        return jdbc.query(sql, rowMapper);
    }

    // --- findById ---

    public Optional<Gpu> findById(int id) {
        String sql = "SELECT id, name, type, status, created_at FROM gpus WHERE id = ?";
        return jdbc.query(sql, rowMapper, id)
                .stream()
                .findFirst();
    }

    // --- updateStatus (used by scheduler/job lifecycle) ---

    public void updateStatus(int id, GpuStatus status) {
        String sql = "UPDATE gpus SET status = ? WHERE id = ?";
        int affected = jdbc.update(sql, status.name(), id);
        if (affected == 0) {
            throw new GpuNotFoundException(id);
        }
    }
}