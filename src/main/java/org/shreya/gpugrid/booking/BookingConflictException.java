package org.shreya.gpugrid.booking;

public class BookingConflictException extends RuntimeException {

    public BookingConflictException(int gpuId) {
        super("GPU " + gpuId + " is already booked for the requested time slot");
    }
}