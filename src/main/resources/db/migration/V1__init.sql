-- V1__init.sql
-- GPUGrid initial schema

-- GPU inventory
CREATE TABLE gpus (
                      id          SERIAL PRIMARY KEY,
                      name        VARCHAR(100) NOT NULL,
                      type        VARCHAR(50),
                      status      VARCHAR(20) DEFAULT 'AVAILABLE',
                      created_at  TIMESTAMP DEFAULT NOW()
);

-- Bookings (core conflict-detection table)
CREATE TABLE bookings (
                          id          SERIAL PRIMARY KEY,
                          gpu_id      INTEGER REFERENCES gpus(id),
                          user_id     VARCHAR(100) NOT NULL,
                          start_time  TIMESTAMP NOT NULL,
                          end_time    TIMESTAMP NOT NULL,
                          status      VARCHAR(20) DEFAULT 'PENDING',
                          priority    INTEGER DEFAULT 0,
                          created_at  TIMESTAMP DEFAULT NOW()
);

-- Job execution tracking
CREATE TABLE jobs (
                      id              SERIAL PRIMARY KEY,
                      booking_id      INTEGER REFERENCES bookings(id),
                      container_id    VARCHAR(200),
                      status          VARCHAR(20) DEFAULT 'PENDING',
                      started_at      TIMESTAMP,
                      completed_at    TIMESTAMP,
                      error_message   TEXT
);

-- Utilization log
CREATE TABLE gpu_utilization_log (
                                     id          SERIAL PRIMARY KEY,
                                     gpu_id      INTEGER REFERENCES gpus(id),
                                     job_id      INTEGER REFERENCES jobs(id),
                                     logged_at   TIMESTAMP DEFAULT NOW(),
                                     state       VARCHAR(20)
);

-- Indexes for conflict detection query performance
CREATE INDEX idx_bookings_gpu_id       ON bookings(gpu_id);
CREATE INDEX idx_bookings_status       ON bookings(status);
CREATE INDEX idx_bookings_time_range   ON bookings(gpu_id, start_time, end_time);
CREATE INDEX idx_jobs_booking_id       ON jobs(booking_id);
CREATE INDEX idx_jobs_status           ON jobs(status);
CREATE INDEX idx_util_log_gpu_id       ON gpu_utilization_log(gpu_id);
CREATE INDEX idx_util_log_logged_at    ON gpu_utilization_log(logged_at);