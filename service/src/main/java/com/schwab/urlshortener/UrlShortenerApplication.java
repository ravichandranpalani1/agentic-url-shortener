package com.schwab.urlshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point. All wiring lives in {@code config.AppConfig} and
 * the {@code web} package's {@code @RestController}s; this class only starts
 * the application context.
 */
@SpringBootApplication
public class UrlShortenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
