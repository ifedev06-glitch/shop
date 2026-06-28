package com.example.shopsecure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@Slf4j
@SpringBootApplication
@EnableCaching
public class ShopsecureApplication {

	public static void main(String[] args) {
		SpringApplication.run(ShopsecureApplication.class, args);
		log.info("ShopSecure application started");
	}

}
