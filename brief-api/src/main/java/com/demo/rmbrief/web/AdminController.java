package com.demo.rmbrief.web;

import com.demo.rmbrief.crm.Client;
import com.demo.rmbrief.plaid.PlaidBootstrapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final PlaidBootstrapService bootstrapService;

    public AdminController(PlaidBootstrapService bootstrapService) {
        this.bootstrapService = bootstrapService;
    }

    /**
     * Bootstraps a Plaid item for an existing CRM client.
     *
     * Example:
     *   POST /admin/seed-client
     *   {"clientId":"client_002","persona":"user_good","plantLargeOutflow":true}
     */
    @PostMapping("/seed-client")
    public ResponseEntity<SeedClientResponse> seedClient(@RequestBody SeedClientRequest req) {
        try {
            Client client = bootstrapService.bootstrapClient(req.clientId(), req.persona());

            if (req.plantLargeOutflow()) {
                double amount = req.outflowAmount() != null ? req.outflowAmount() : 50000.00;
                // 7 days ago: within Plaid's 14-day posting window and inside the 30-day delta window
                LocalDate txDate = LocalDate.now().minusDays(7);
                bootstrapService.plantTransaction(
                        client.getPlaidAccessToken(),
                        amount,
                        "Wire Transfer - Business Payment",
                        txDate
                );
            }

            return ResponseEntity.ok(new SeedClientResponse(
                    client.getClientId(),
                    client.getPlaidItemId(),
                    "OK"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    new SeedClientResponse(req.clientId(), null, "ERROR: " + e.getMessage()));
        } catch (IOException | RuntimeException e) {
            log.error("seed-client failed for {}: {}", req.clientId(), e.getMessage(), e);
            // Include itemId if bootstrap succeeded but plant failed
            String itemId = null;
            try { itemId = bootstrapService.getPlaidItemId(req.clientId()); } catch (Exception ignored) {}
            return ResponseEntity.internalServerError().body(
                    new SeedClientResponse(req.clientId(), itemId, "ERROR: " + e.getMessage()));
        }
    }

    public record SeedClientRequest(
            String clientId,
            String persona,              // e.g. "user_good", "user_yuppie" — null = Plaid default
            boolean plantLargeOutflow,   // inject a synthetic $50k outflow (hero demo)
            Double outflowAmount         // override the default $50k if needed
    ) {}

    public record SeedClientResponse(
            String clientId,
            String plaidItemId,
            String message
    ) {}
}
