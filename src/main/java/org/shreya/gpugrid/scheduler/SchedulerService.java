package org.shreya.gpugrid.scheduler;

import org.shreya.gpugrid.booking.Booking;
import org.shreya.gpugrid.booking.BookingRepository;
import org.shreya.gpugrid.booking.BookingStatus;
import org.shreya.gpugrid.job.Job;
import org.shreya.gpugrid.job.JobLifecycleManager;
import org.shreya.gpugrid.job.JobRepository;
import org.shreya.gpugrid.job.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final BookingRepository bookingRepository;
    private final JobRepository jobRepository;
    private final JobLifecycleManager jobLifecycleManager;

    @Value("${gpugrid.scheduler.strategy:FIFO}")
    private String strategy;

    public SchedulerService(BookingRepository bookingRepository,
                             JobRepository jobRepository,
                             JobLifecycleManager jobLifecycleManager) {
        this.bookingRepository  = bookingRepository;
        this.jobRepository      = jobRepository;
        this.jobLifecycleManager = jobLifecycleManager;
    }

    @Scheduled(fixedDelayString = "${gpugrid.scheduler.poll-interval-ms:5000}")
    public void dispatchReadyBookings() {
        List<Booking> reserved = bookingRepository.findByStatus(BookingStatus.RESERVED);

        List<Booking> ready = reserved.stream()
                .filter(b -> !b.startTime().isAfter(LocalDateTime.now()))  // slot has started
                .filter(b -> jobRepository.findByBookingId(b.id()).isEmpty()) // no job yet
                .sorted(comparator())
                .toList();

        if (!ready.isEmpty()) {
            log.info("Scheduler [{}]: {} booking(s) ready to dispatch", strategy, ready.size());
        }

        for (Booking booking : ready) {
            try {
                jobLifecycleManager.launch(booking);
            } catch (Exception e) {
                log.error("Scheduler: failed to launch booking id={}: {}", booking.id(), e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelayString = "${gpugrid.scheduler.poll-interval-ms:5000}")
    public void pollRunningJobs() {
        List<Job> running = jobRepository.findByStatus(JobStatus.RUNNING);

        for (Job job : running) {
            try {
                jobLifecycleManager.poll(job);
            } catch (Exception e) {
                log.error("Scheduler: error polling job id={}: {}", job.id(), e.getMessage());
            }
        }
    }

    // --- comparator based on strategy ---

    private java.util.Comparator<Booking> comparator() {
        if ("PRIORITY".equalsIgnoreCase(strategy)) {
            // Higher priority number = dispatched first; ties broken by created_at (FIFO)
            return java.util.Comparator
                    .comparingInt(Booking::priority).reversed()
                    .thenComparing(Booking::createdAt);
        }
        // Default: FIFO
        return java.util.Comparator.comparing(Booking::createdAt);
    }
}
