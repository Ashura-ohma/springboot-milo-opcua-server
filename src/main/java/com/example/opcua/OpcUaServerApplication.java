package com.example.opcua;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class OpcUaServerApplication  {


    public static void main(String[] args) {
        SpringApplication.run(OpcUaServerApplication.class, args);
    }

}
