package com.campus.studyroom.repair;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RepairApplication {
  @Bean
  com.campus.studyroom.security.BusinessRequestTracker businessRequestTracker() {
    return new com.campus.studyroom.security.BusinessRequestTracker();
  }

  public static void main(String[] args) {
    SpringApplication.run(RepairApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
