package org.shreya.gpugrid;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GpugridApplication {

    public static void main(String[] args) {
        System.setProperty("user.timezone", "Asia/Kolkata");

        SpringApplication.run(GpugridApplication.class, args);
    }

}
