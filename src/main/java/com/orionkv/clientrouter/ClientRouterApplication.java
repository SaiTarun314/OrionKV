package com.orionkv.clientrouter;

import com.orionkv.clientrouter.config.ClientRouterProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.orionkv.clientrouter")
@EnableScheduling
@EnableConfigurationProperties(ClientRouterProperties.class)
public class ClientRouterApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClientRouterApplication.class, args);
    }
}
