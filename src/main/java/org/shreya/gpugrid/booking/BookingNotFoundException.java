package org.shreya.gpugrid.booking;

public class BookingNotFoundException extends RuntimeException {

    public BookingNotFoundException(int id) {
        super("Booking not found with id: " + id);
    }
}