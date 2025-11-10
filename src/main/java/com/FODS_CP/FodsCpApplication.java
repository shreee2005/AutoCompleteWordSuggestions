package com.FODS_CP;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class FodsCpApplication {

	public static void main(String[] args) {
		SpringApplication.run(FodsCpApplication.class, args);
	}

}
