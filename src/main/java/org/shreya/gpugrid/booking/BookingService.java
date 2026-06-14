package org.shreya.gpugrid.booking;

import org.shreya.gpugrid.inventory.GpuNotFoundException;
import org.shreya.gpugrid.inventory.GpuRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final GpuRepository gpuRepository;

    public BookingService(BookingRepository bookingRepository, GpuRepository gpuRepository) {
        this.bookingRepository = bookingRepository;
        this.gpuRepository = gpuRepository;
    }

    @Transactional
    public Booking book(Integer gpuId, String userId,
                        LocalDateTime startTime, LocalDateTime endTime,
                        Integer priority) {

        // 1. Validate inputs
        validateInputs(gpuId, userId, startTime, endTime);

        // 2. Confirm the GPU exists
        gpuRepository.findById(gpuId)
                .orElseThrow(() -> new GpuNotFoundException(gpuId));

        // 3. Conflict check (acquires row-level lock on the gpu row)
        if (bookingRepository.hasConflict(gpuId, startTime, endTime, null)) {
            throw new BookingConflictException(gpuId);
        }

        // 4. Create and persist the booking
        Booking booking = Booking.create(gpuId, userId, startTime, endTime, priority);
        Booking saved = bookingRepository.save(booking);

        // 5. Immediately promote to RESERVED (slot is confirmed)
        bookingRepository.updateStatus(saved.id(), BookingStatus.RESERVED);

        return bookingRepository.findById(saved.id())
                .orElseThrow(() -> new IllegalStateException("Failed to reload booking after save"));
    }

    public List<Booking> listAll(String userId, Integer gpuId, String status) {
        if (userId != null) {
            return bookingRepository.findByUserId(userId);
        }
        if (gpuId != null) {
            return bookingRepository.findByGpuId(gpuId);
        }
        if (status != null) {
            BookingStatus bookingStatus;
            try {
                bookingStatus = BookingStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid status value: " + status);
            }
            return bookingRepository.findByStatus(bookingStatus);
        }
        return bookingRepository.findAll();
    }

    public Booking getById(int id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> new BookingNotFoundException(id));
    }

    @Transactional
    public Booking cancel(int id) {
        Booking booking = getById(id);

        if (booking.status() == BookingStatus.RUNNING) {
            throw new IllegalStateException(
                    "Cannot cancel booking " + id + " — job is already RUNNING");
        }
        if (booking.status() == BookingStatus.COMPLETED
                || booking.status() == BookingStatus.FAILED
                || booking.status() == BookingStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Cannot cancel booking " + id + " — already in terminal state: " + booking.status());
        }

        bookingRepository.updateStatus(id, BookingStatus.CANCELLED);

        return bookingRepository.findById(id)
                .orElseThrow(() -> new BookingNotFoundException(id));
    }

    // --- private helpers ---

    private void validateInputs(Integer gpuId, String userId,
                                LocalDateTime startTime, LocalDateTime endTime) {
        if (gpuId == null) {
            throw new IllegalArgumentException("gpuId must not be null");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException("startTime and endTime must not be null");
        }
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("endTime must be after startTime");
        }
        if (startTime.isBefore(LocalDateTime.now().minusHours(1))) {
            throw new IllegalArgumentException("startTime cannot be more than 1 hour in the past");
        }
    }
}
