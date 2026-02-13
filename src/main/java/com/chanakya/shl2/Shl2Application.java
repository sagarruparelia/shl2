package com.chanakya.shl2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Shl2Application {

    public static void main(String[] args) {
        SpringApplication.run(Shl2Application.class, args);
    }

}
