package com.demo.cardsubscription.exposition.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI cardSubscriptionOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Card Subscription API")
                        .description("Manages card subscription lifecycle via REST and Kafka events")
                        .version("1.0.0"));
    }
}
