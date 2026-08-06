package com.innogrid.aims;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AimsCloudBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(AimsCloudBackendApplication.class, args);
	}

}
