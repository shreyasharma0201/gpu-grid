package org.shreya.gpugrid.job;

import org.shreya.gpugrid.booking.Booking;
import org.shreya.gpugrid.booking.BookingRepository;
import org.shreya.gpugrid.booking.BookingStatus;
import org.shreya.gpugrid.executor.GpuExecutor;
import org.shreya.gpugrid.executor.JobExecutionStatus;
import org.shreya.gpugrid.inventory.GpuRepository;
import org.shreya.gpugrid.inventory.GpuStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class JobLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(JobLifecycleManager.class);

    private final JobRepository jobRepository;
    private final BookingRepository bookingRepository;
    private final GpuRepository gpuRepository;
    private final GpuExecutor gpuExecutor;

    public JobLifecycleManager(JobRepository jobRepository,
                                BookingRepository bookingRepository,
                                GpuRepository gpuRepository,
                                GpuExecutor gpuExecutor) {
        this.jobRepository     = jobRepository;
        this.bookingRepository = bookingRepository;
        this.gpuRepository     = gpuRepository;
        this.gpuExecutor       = gpuExecutor;
    }

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

        // 3. Update job
        Job runningJob = new Job(
                job.id(),
                job.bookingId(),
                executionId,
                JobStatus.RUNNING,
                LocalDateTime.now(),
                null,
                null
        );
        Job savedJob = jobRepository.save(runningJob);



        // 4. Update booking → RUNNING
        bookingRepository.updateStatus(booking.id(), BookingStatus.RUNNING);

        // 5. Update GPU status → RUNNING
        gpuRepository.updateStatus(booking.gpuId(), GpuStatus.RUNNING);

        log.info("Job id={} RUNNING on GPU {} (executionId={})", job.id(), booking.gpuId(), executionId);
        return savedJob;
    }

    @Transactional
    public void poll(Job job) {
        JobExecutionStatus execStatus = gpuExecutor.getStatus(job.containerId());

        switch (execStatus) {
            case RUNNING -> { /* nothing to do */ }

            case COMPLETED -> {
                log.info("Job id={} COMPLETED", job.id());
                complete(job);
            }

            case FAILED -> {
                log.warn("Job id={} FAILED (reported by executor)", job.id());
                Booking booking = bookingRepository.findById(job.bookingId())
                        .orElseThrow(() -> new IllegalStateException("Booking not found for job " + job.id()));
                fail(job, booking, "Executor reported FAILED");
            }
        }
    }

    // --- private helpers ---

    private void complete(Job job) {
        // Update job → COMPLETED
        Job completed = new Job(
                job.id(), job.bookingId(), job.containerId(),
                JobStatus.COMPLETED, job.startedAt(), LocalDateTime.now(), null
        );
        jobRepository.save(completed);

        // Update booking → COMPLETED
        bookingRepository.updateStatus(job.bookingId(), BookingStatus.COMPLETED);

        // Release GPU → AVAILABLE
        bookingRepository.findById(job.bookingId()).ifPresent(booking ->
                gpuRepository.updateStatus(booking.gpuId(), GpuStatus.AVAILABLE)
        );
    }

    private void fail(Job job, Booking booking, String reason) {
        Job failed = new Job(
                job.id(), job.bookingId(), job.containerId(),
                JobStatus.FAILED, job.startedAt(), LocalDateTime.now(), reason
        );
        jobRepository.save(failed);
        bookingRepository.updateStatus(booking.id(), BookingStatus.FAILED);
        gpuRepository.updateStatus(booking.gpuId(), GpuStatus.AVAILABLE);
    }
}
