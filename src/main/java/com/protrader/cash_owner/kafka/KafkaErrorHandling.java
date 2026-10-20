package com.protrader.cash_owner.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;

@Configuration
public class KafkaErrorHandling {

    @Bean
    public CommonErrorHandler stopOnErrorHandler() {
        return new CommonContainerStoppingErrorHandler();
    }
}
