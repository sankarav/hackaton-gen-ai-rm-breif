package com.demo.rmbrief.plaid;

import com.plaid.client.ApiClient;
import com.plaid.client.request.PlaidApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;

@Configuration
public class PlaidConfig {

    @Value("${PLAID_CLIENT_ID}")
    private String clientId;

    @Value("${PLAID_SECRET}")
    private String secret;

    @Value("${PLAID_ENV:sandbox}")
    private String environment;

    @Bean
    public PlaidApi plaidApi() {
        HashMap<String, String> apiKeys = new HashMap<>();
        // plaid-java expects these exact key names
        apiKeys.put("clientId", clientId);
        apiKeys.put("secret", secret);

        ApiClient apiClient = new ApiClient(apiKeys);
        apiClient.setPlaidAdapter(resolveBaseUrl());

        return apiClient.createService(PlaidApi.class);
    }

    private String resolveBaseUrl() {
        return switch (environment.toLowerCase()) {
            case "production" -> ApiClient.Production;
            default -> ApiClient.Sandbox;
        };
    }
}
