package com.demo.rmbrief.plaid;

import com.demo.rmbrief.crm.Client;
import com.demo.rmbrief.crm.ClientRepository;
import com.plaid.client.request.PlaidApi;
import com.plaid.client.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import retrofit2.Response;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

@Service
public class PlaidBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(PlaidBootstrapService.class);

    private static final String INSTITUTION_ID = "ins_109508"; // First Platypus Bank (sandbox)
    private static final List<Products> PRODUCTS = List.of(
            Products.TRANSACTIONS,
            Products.IDENTITY,
            Products.INVESTMENTS,
            Products.LIABILITIES
    );

    private final PlaidApi plaidApi;
    private final ClientRepository clientRepository;

    public PlaidBootstrapService(PlaidApi plaidApi, ClientRepository clientRepository) {
        this.plaidApi = plaidApi;
        this.clientRepository = clientRepository;
    }

    /**
     * Creates a Plaid Sandbox item for the given client, exchanges for an access_token,
     * and persists both to the client row. Idempotent: skips if already seeded.
     */
    @Transactional
    public Client bootstrapClient(String clientId, String persona) throws IOException {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + clientId));

        if (client.getPlaidAccessToken() != null) {
            log.info("Client {} already has a Plaid item ({}), skipping bootstrap", clientId, client.getPlaidItemId());
            return client;
        }

        // Step 1: Create sandbox public token
        SandboxPublicTokenCreateRequest tokenRequest = new SandboxPublicTokenCreateRequest()
                .institutionId(INSTITUTION_ID)
                .initialProducts(PRODUCTS);

        if (persona != null && !persona.isBlank()) {
            tokenRequest.options(new SandboxPublicTokenCreateRequestOptions().overrideUsername(persona));
        }

        log.info("Creating Plaid sandbox public token for client {} (persona={})", clientId, persona);
        Response<SandboxPublicTokenCreateResponse> tokenResp =
                plaidApi.sandboxPublicTokenCreate(tokenRequest).execute();
        assertSuccess(tokenResp, "sandboxPublicTokenCreate");

        String publicToken = tokenResp.body().getPublicToken();
        log.debug("Got public token: {}...", publicToken.substring(0, Math.min(20, publicToken.length())));

        // Step 2: Exchange public token → access_token
        ItemPublicTokenExchangeRequest exchangeRequest = new ItemPublicTokenExchangeRequest()
                .publicToken(publicToken);

        log.info("Exchanging public token for access_token (client {})", clientId);
        Response<ItemPublicTokenExchangeResponse> exchangeResp =
                plaidApi.itemPublicTokenExchange(exchangeRequest).execute();
        assertSuccess(exchangeResp, "itemPublicTokenExchange");

        String accessToken = exchangeResp.body().getAccessToken();
        String itemId = exchangeResp.body().getItemId();
        log.info("Plaid item created: itemId={} for client {}", itemId, clientId);

        // Step 3: Persist
        client.setPlaidAccessToken(accessToken);
        client.setPlaidItemId(itemId);
        return clientRepository.save(client);
    }

    /**
     * Plants a synthetic large outflow transaction into a sandbox item.
     * Plaid sandbox always assigns custom transactions to the default depository account —
     * per-account targeting is not supported by the API.
     */
    public void plantTransaction(String accessToken, double amount, String name, LocalDate date) throws IOException {
        CustomSandboxTransaction txn = new CustomSandboxTransaction()
                .amount(amount)                  // positive = debit/outflow in Plaid's model
                .dateTransacted(date)
                .datePosted(date)
                .description(name)
                .isoCurrencyCode("USD");

        SandboxTransactionsCreateRequest req = new SandboxTransactionsCreateRequest()
                .accessToken(accessToken)
                .transactions(List.of(txn));

        log.info("Planting sandbox transaction: {} ${} on {}", name, amount, date);
        Response<SandboxTransactionsCreateResponse> resp =
                plaidApi.sandboxTransactionsCreate(req).execute();
        assertSuccess(resp, "sandboxTransactionsCreate");
        log.info("Transaction planted successfully");
    }

    /**
     * Clears the stored Plaid credentials for a client so bootstrapClient will
     * re-run the full public_token flow on the next call.
     */
    @Transactional
    public void clearPlaidItem(String clientId) {
        clientRepository.findById(clientId).ifPresent(c -> {
            c.setPlaidAccessToken(null);
            c.setPlaidItemId(null);
            clientRepository.save(c);
            log.info("Cleared Plaid item for client {}", clientId);
        });
    }

    public String getPlaidItemId(String clientId) {
        return clientRepository.findById(clientId).map(Client::getPlaidItemId).orElse(null);
    }

    private static <T> void assertSuccess(Response<T> response, String operation) throws IOException {
        if (!response.isSuccessful() || response.body() == null) {
            String body = response.errorBody() != null ? response.errorBody().string() : "(no body)";
            throw new RuntimeException("Plaid " + operation + " failed [HTTP " + response.code() + "]: " + body);
        }
    }
}
