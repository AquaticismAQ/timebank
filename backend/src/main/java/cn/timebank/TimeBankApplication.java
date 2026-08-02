package cn.timebank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Single deployable modular monolith (Design.md §5, D11). Virtual threads are
 * enabled via spring.threads.virtual.enabled in application.yml (D3), not here.
 */
@SpringBootApplication
@EnableScheduling
public class TimeBankApplication {

    public static void main(String[] args) {
        SpringApplication.run(TimeBankApplication.class, args);
    }
}
