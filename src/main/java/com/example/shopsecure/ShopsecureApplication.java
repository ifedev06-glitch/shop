package com.example.shopsecure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class ShopsecureApplication {

	public static void main(String[] args) {
		SpringApplication.run(ShopsecureApplication.class, args);
	}

}
