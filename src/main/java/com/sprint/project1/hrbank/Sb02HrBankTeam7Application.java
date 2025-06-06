package com.sprint.project1.hrbank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableJpaAuditing
@EnableScheduling
public class Sb02HrBankTeam7Application {

  public static void main(String[] args) {
    SpringApplication.run(Sb02HrBankTeam7Application.class, args);
  }

}
