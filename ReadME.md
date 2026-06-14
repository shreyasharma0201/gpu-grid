# GPUGrid

**Slot-based GPU booking and job scheduling system built with Java + Spring Boot + PostgreSQL.**

Think of it as *Calendly for shared GPU infrastructure* — multiple users book time slots on a shared GPU pool, with conflict-free concurrent allocation enforced at the database level.

> Built to demonstrate: concurrency control, pessimistic locking, clean interface-driven design, and production-style modular monolith architecture.

---

## What makes this technically interesting

### The hard problem: race conditions under concurrent load
When two users simultaneously request the same GPU slot, a naive implementation lets both through — double-booking. GPUGrid prevents this using **PostgreSQL row-level pessimistic locking**:

```sql
-- Step 1: Lock the GPU row so no other transaction can touch it
SELECT id FROM gpus WHERE id = ? FOR UPDATE;

-- Step 2: Now safely check for overlapping bookings
SELECT COUNT(*) FROM bookings
WHERE gpu_id = ?
  AND status IN ('RESERVED', 'RUNNING')
  AND start_time < ?   -- requested end
  AND end_time   > ?;  -- requested start
```

This runs inside a `@Transactional` method. The `FOR UPDATE` lock is held until the transaction commits — guaranteeing exactly one booking succeeds even under 20+ concurrent requests.

### The clean abstraction: GpuExecutor interface
Business logic never touches Docker, CUDA, or any GPU API directly:

```java
public interface GpuExecutor {
    String startJob(Job job, int gpuIndex);
    void stopJob(String executionId);
    JobExecutionStatus getStatus(String executionId);
    List<GpuInfo> listAvailableGpus();
}
```

`MockGpuExecutor` (`@Profile("dev")`) simulates job execution with `Thread.sleep` — runs on any laptop with no GPU. `DockerGpuExecutor` (`@Profile("prod")`) issues real `docker run --gpus` commands. Swap with zero business logic changes.

---

## Architecture

<img width="1024" height="559" alt="image" src="https://github.com/user-attachments/assets/5f8723ed-7ed2-43ca-b46a-64622a1abcbf" />

## Live Dashboard

<img width="1891" height="952" alt="image" src="https://github.com/user-attachments/assets/930759e5-6dc4-40a7-a446-c52683edccd2" />

Real-time monitoring dashboard showing:

- GPU inventory and status
- Utilization metrics
- Cost savings analytics
- Per-GPU breakdown
- Scheduler activity

## Module structure

```
src/main/java/org/shreya/gpugrid/
├── inventory/     GPU registry — tracks id, name, type, status
├── booking/       Slot reservation — conflict detection, FOR UPDATE locking
├── scheduler/     Queue management — FIFO and PRIORITY dispatch
├── job/           Job lifecycle — PENDING → RUNNING → COMPLETED/FAILED
├── executor/      GpuExecutor interface + Mock + Docker implementations
├── reporting/     Utilization metrics, cost savings calculations
└── api/           REST controllers, DTOs, global error handling
```

## Job lifecycle

```
PENDING → RESERVED → RUNNING → COMPLETED
                              → FAILED
                   → CANCELLED
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.x |
| Database | PostgreSQL 15 |
| DB Access | Spring JDBC — no JPA/Hibernate |
| Migrations | Flyway |
| Containerization | Docker + Docker Compose |
| GPU Runtime (prod) | NVIDIA Container Runtime |
| Testing | JUnit 5 |
| Build | Maven |

**Why JDBC over JPA?** Direct JDBC gives fine-grained control over transactions and locking. JPA's hidden query generation is a liability when `SELECT ... FOR UPDATE` semantics are critical.

---

## Quickstart

**Prerequisites:** Java 17+, Maven, Docker Desktop

```bash
# 1. Clone
git clone https://github.com/YOUR_USERNAME/gpugrid.git
cd gpugrid

# 2. Start PostgreSQL
docker compose up -d postgres

# 3. Run (Windows PowerShell)
$env:SPRING_PROFILES_ACTIVE="dev"
./mvnw spring-boot:run

# 3. Run (Linux/macOS)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# 4. Open dashboard
# http://localhost:8080
```

---

## API walkthrough

### Register GPUs
```bash
curl -X POST http://localhost:8080/api/gpus \
  -H "Content-Type: application/json" \
  -d '{"name":"gpu-0","type":"MockA100"}'

curl -X POST http://localhost:8080/api/gpus \
  -H "Content-Type: application/json" \
  -d '{"name":"gpu-1","type":"MockA100"}'
```

### Book a slot
```bash
curl -X POST http://localhost:8080/api/bookings \
  -H "Content-Type: application/json" \
  -d '{
    "gpuId": 1,
    "userId": "alice",
    "startTime": "2030-01-01T10:00:00",
    "endTime":   "2030-01-01T12:00:00",
    "priority": 0
  }'
```

### Try to double-book the same slot → 409 Conflict
```bash
curl -X POST http://localhost:8080/api/bookings \
  -H "Content-Type: application/json" \
  -d '{
    "gpuId": 1,
    "userId": "charlie",
    "startTime": "2030-01-01T10:30:00",
    "endTime":   "2030-01-01T11:30:00"
  }'
# → 409 Conflict
```

### Watch the scheduler dispatch jobs
```bash
# Within 5 seconds the scheduler picks up RESERVED bookings
curl http://localhost:8080/api/jobs

# GPU status flips to RUNNING
curl http://localhost:8080/api/gpus
```

### Reports
```bash
curl "http://localhost:8080/api/reports/utilization?from=2020-01-01T00:00:00&to=2099-01-01T00:00:00"
curl "http://localhost:8080/api/reports/cost-savings?from=2020-01-01T00:00:00&to=2099-01-01T00:00:00"
curl "http://localhost:8080/api/reports/queue-depth"
```

---

## Concurrency test

Fires 20 simultaneous booking requests for the same slot. Asserts exactly 1 succeeds.

```bash
./mvnw test "-Dtest=ConcurrentBookingTest"
```

Expected:
```
=== Concurrency Test Results ===
Success  (201): 1
Conflict (409): 19
Errors        : 0
```

---

## Scheduler strategies

```properties
gpugrid.scheduler.strategy=FIFO      # default — first come, first served
gpugrid.scheduler.strategy=PRIORITY  # higher priority number dispatched first
```

---

## API Reference

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/gpus` | Register a GPU |
| GET | `/api/gpus` | List all GPUs |
| GET | `/api/gpus/{id}` | Get GPU by ID |
| POST | `/api/bookings` | Create a booking |
| GET | `/api/bookings` | List bookings (filter: `?userId=`, `?gpuId=`, `?status=`) |
| GET | `/api/bookings/{id}` | Get booking by ID |
| DELETE | `/api/bookings/{id}` | Cancel a booking |
| GET | `/api/jobs` | List all jobs |
| GET | `/api/jobs/{id}` | Get job by ID |
| GET | `/api/reports/utilization` | GPU utilization % over time range |
| GET | `/api/reports/cost-savings` | Estimated cost savings vs idle baseline |
| GET | `/api/reports/queue-depth` | Pending bookings per GPU |

---
