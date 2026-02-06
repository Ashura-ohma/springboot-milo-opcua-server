package com.example.opcua;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@EnableScheduling
@SpringBootApplication
public class OpcUaServerApplication  {


    public static void main(String[] args) {
        SpringApplication.run(OpcUaServerApplication.class, args);
        log.error("error ......" );
        log.info("info ......");
        log.warn("warn ......");

    }

}
