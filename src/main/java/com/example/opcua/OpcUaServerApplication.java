package com.example.opcua;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;

@SpringBootApplication
public class OpcUaServerApplication {
    public static void main(String[] args) throws InterruptedException {
        CountDownLatch closed = new CountDownLatch(1);
        SpringApplication application = new SpringApplication(OpcUaServerApplication.class);
        application.setDefaultProperties(Collections.singletonMap(
                "spring.config.name", "application,opcua-demo"));
        application.addListeners((ApplicationListener<ContextClosedEvent>) event -> closed.countDown());
        ConfigurableApplicationContext context = application.run(args);
        // Milo uses daemon workers. Keep only the standalone main thread alive;
        // embedded consumers have no extra keep-alive thread or shutdown hook.
        if (!context.getEnvironment().getProperty("spring.opcua.server.enabled", Boolean.class, true)) {
            context.close();
            return;
        }
        try {
            closed.await();
        } catch (InterruptedException e) {
            context.close();
            Thread.currentThread().interrupt();
            throw e;
        }
    }
}
