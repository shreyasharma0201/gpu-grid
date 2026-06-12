package org.shreya.gpugrid.api;

import org.shreya.gpugrid.booking.BookingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    // POST /api/bookings
    @PostMapping
    public ResponseEntity<BookingResponse> book(@RequestBody BookingRequest request) {
        var booking = bookingService.book(
                request.gpuId(),
                request.userId(),
                request.startTime(),
                request.endTime(),
                request.priority()
        );
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(BookingResponse.from(booking));
    }

    // GET /api/bookings  (optional ?userId=  ?gpuId=  ?status=)
    @GetMapping
    public ResponseEntity<List<BookingResponse>> listAll(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) Integer gpuId,
            @RequestParam(required = false) String status
    ) {
        var bookings = bookingService.listAll(userId, gpuId, status)
                .stream()
                .map(BookingResponse::from)
                .toList();
        return ResponseEntity.ok(bookings);
    }

    // GET /api/bookings/{id}
    @GetMapping("/{id}")
    public ResponseEntity<BookingResponse> getById(@PathVariable int id) {
        return ResponseEntity.ok(BookingResponse.from(bookingService.getById(id)));
    }

    // DELETE /api/bookings/{id}  → cancels the booking
    @DeleteMapping("/{id}")
    public ResponseEntity<BookingResponse> cancel(@PathVariable int id) {
        return ResponseEntity.ok(BookingResponse.from(bookingService.cancel(id)));
    }
}
