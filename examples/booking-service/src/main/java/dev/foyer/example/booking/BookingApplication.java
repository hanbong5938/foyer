package dev.foyer.example.booking;

import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class BookingApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookingApplication.class, args);
    }

    @RestController
    static class BookingController {

        @GetMapping(path = "/", produces = MediaType.TEXT_HTML_VALUE)
        String index() {
            return """
                    <!doctype html>
                    <html><head><meta charset="utf-8"><title>Concert 2026</title></head>
                    <body><h1>Concert 2026</h1><p><a href="booking/seats">Book seats</a></p></body></html>
                    """;
        }

        /** Protected by {@code foyer.rooms.concert-2026.protect}: only reachable with an admission token. */
        @GetMapping("/booking/seats")
        Map<String, List<String>> seats() {
            return Map.of("seats", List.of("A1", "A2", "A3", "B1", "B2"));
        }
    }
}
