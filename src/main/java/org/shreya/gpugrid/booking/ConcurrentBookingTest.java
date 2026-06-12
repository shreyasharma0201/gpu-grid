package org.shreya.gpugrid.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("dev")
class ConcurrentBookingTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("gpugrid_test")
            .withUsername("gpugrid")
            .withPassword("secret");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    BookingRepository bookingRepository;

    String baseUrl;
    int gpuId;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;

        // Register one GPU
        var gpuBody = Map.of("name", "test-gpu-0", "type", "MockA100");
        ResponseEntity<Map> gpuResp = restTemplate.postForEntity(
                baseUrl + "/api/gpus", gpuBody, Map.class);
        gpuId = (Integer) gpuResp.getBody().get("id");
    }

    @Test
    void onlyOneBookingSucceedsForSameSlot() throws InterruptedException {
        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready  = new CountDownLatch(threads); // all threads signal ready
        CountDownLatch start  = new CountDownLatch(1);       // release all at once
        CountDownLatch done   = new CountDownLatch(threads);

        AtomicInteger successCount  = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger errorCount    = new AtomicInteger(0);

        // All threads request the exact same time slot on the same GPU
        String body = """
                {
                  "gpuId": %d,
                  "userId": "stress-user",
                  "startTime": "2030-06-01T10:00:00",
                  "endTime":   "2030-06-01T12:00:00"
                }
                """.formatted(gpuId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();  // wait for the gun

                    ResponseEntity<Map> resp = restTemplate.postForEntity(
                            baseUrl + "/api/bookings", request, Map.class);

                    if (resp.getStatusCode() == HttpStatus.CREATED) {
                        successCount.incrementAndGet();
                    } else if (resp.getStatusCode() == HttpStatus.CONFLICT) {
                        conflictCount.incrementAndGet();
                    } else {
                        errorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }));
        }

        ready.await();   // wait until all threads are primed
        start.countDown(); // fire!
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        System.out.printf(
            "Results → success: %d | conflict: %d | error: %d%n",
            successCount.get(), conflictCount.get(), errorCount.get()
        );

        // Core assertion: exactly 1 booking must succeed
        assertThat(successCount.get())
                .as("Exactly one booking should succeed for the same slot")
                .isEqualTo(1);

        assertThat(conflictCount.get())
                .as("All other threads should get 409 Conflict")
                .isEqualTo(threads - 1);

        assertThat(errorCount.get())
                .as("No unexpected errors")
                .isEqualTo(0);

        // Also verify at DB level: only 1 RESERVED booking exists for that slot
        List<Booking> reserved = bookingRepository.findByStatus(BookingStatus.RESERVED);
        assertThat(reserved).hasSize(1);
        assertThat(reserved.get(0).gpuId()).isEqualTo(gpuId);
    }
}
