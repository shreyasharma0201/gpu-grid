package org.shreya.gpugrid.job;

import org.shreya.gpugrid.booking.Booking;
import org.shreya.gpugrid.booking.BookingRepository;
import org.shreya.gpugrid.booking.BookingStatus;
import org.shreya.gpugrid.executor.GpuExecutor;
import org.shreya.gpugrid.executor.JobExecutionStatus;
import org.shreya.gpugrid.inventory.GpuRepository;
import org.shreya.gpugrid.inventory.GpuStatus;
import org.shreya.gpugrid.reporting.UtilizationTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class JobLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(JobLifecycleManager.class);

    private final JobRepository       jobRepository;
    private final BookingRepository   bookingRepository;
    private final GpuRepository       gpuRepository;
    private final GpuExecutor         gpuExecutor;
    private final UtilizationTracker  utilizationTracker;

    public JobLifecycleManager(JobRepository jobRepository,
                               BookingRepository bookingRepository,
                               GpuRepository gpuRepository,
                               GpuExecutor gpuExecutor,
                               UtilizationTracker utilizationTracker) {
        this.jobRepository      = jobRepository;
        this.bookingRepository  = bookingRepository;
        this.gpuRepository      = gpuRepository;
        this.gpuExecutor        = gpuExecutor;
        this.utilizationTracker = utilizationTracker;
    }

    /**
     * Launch a job for a RESERVED booking.
     * Flow: create Job (PENDING) → startJob() → Job (RUNNING) → Booking (RUNNING) → GPU (RUNNING)
     */
    @Transactional
    public Job launch(Booking booking) {
        log.info("Launching job for booking id={} gpu={} user={}", booking.id(), booking.gpuId(), booking.userId());

        // 1. Create PENDING job
        Job job = jobRepository.save(Job.createForBooking(booking.id()));

        // 2. Start on executor
        String executionId;
        try {
            executionId = gpuExecutor.startJob(job, booking.gpuId() - 1); // DB ids are 1-based
        } catch (Exception e) {
            log.error("GpuExecutor.startJob failed for booking={}: {}", booking.id(), e.getMessage());
            fail(job, booking, "Executor failed to start job: " + e.getMessage());
            throw e;
        }

        // 3. Job → RUNNING
        Job runningJob = new Job(
                job.id(), job.bookingId(), executionId,
                JobStatus.RUNNING, LocalDateTime.now(), null, null
        );
        jobRepository.save(runningJob);

        // 4. Booking → RUNNING
        bookingRepository.updateStatus(booking.id(), BookingStatus.RUNNING);

        // 5. GPU → RUNNING
        gpuRepository.updateStatus(booking.gpuId(), GpuStatus.RUNNING);

        // 6. Log utilization — ACTIVE
        utilizationTracker.logActive(booking.gpuId(), job.id());

        log.info("Job id={} RUNNING on GPU {} (executionId={})", job.id(), booking.gpuId(), executionId);
        return jobRepository.findById(job.id()).orElseThrow();
    }

    /**
     * Poll a running job — update state if it has finished.
     */
    @Transactional
    public void poll(Job job) {
        JobExecutionStatus execStatus = gpuExecutor.getStatus(job.containerId());

        switch (execStatus) {
            case RUNNING -> { /* still going */ }

            case COMPLETED -> {
                log.info("Job id={} COMPLETED", job.id());
                complete(job);
            }

            case FAILED -> {
                log.warn("Job id={} FAILED (executor reported)", job.id());
                Booking booking = bookingRepository.findById(job.bookingId())
                        .orElseThrow(() -> new IllegalStateException("Booking not found for job " + job.id()));
                fail(job, booking, "Executor reported FAILED");
            }
        }
    }

    // --- private helpers ---

    private void complete(Job job) {
        Job completed = new Job(
                job.id(), job.bookingId(), job.containerId(),
                JobStatus.COMPLETED, job.startedAt(), LocalDateTime.now(), null
        );
        jobRepository.save(completed);
        bookingRepository.updateStatus(job.bookingId(), BookingStatus.COMPLETED);

        bookingRepository.findById(job.bookingId()).ifPresent(booking -> {
            gpuRepository.updateStatus(booking.gpuId(), GpuStatus.AVAILABLE);
            // Log utilization — IDLE (GPU now free)
            utilizationTracker.logIdle(booking.gpuId(), job.id());
        });
    }

    private void fail(Job job, Booking booking, String reason) {
        Job failed = new Job(
                job.id(), job.bookingId(), job.containerId(),
                JobStatus.FAILED, job.startedAt(), LocalDateTime.now(), reason
        );
        jobRepository.save(failed);
        bookingRepository.updateStatus(booking.id(), BookingStatus.FAILED);
        gpuRepository.updateStatus(booking.gpuId(), GpuStatus.AVAILABLE);
        utilizationTracker.logIdle(booking.gpuId(), job.id());
    }
}
