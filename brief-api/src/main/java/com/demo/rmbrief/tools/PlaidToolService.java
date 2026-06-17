package com.demo.rmbrief.tools;

import com.demo.rmbrief.crm.ClientRepository;
import com.plaid.client.model.*;
import com.plaid.client.request.PlaidApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import retrofit2.Response;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements the five Plaid read tools used by the agent loop.
 * Every method is read-only; no write/transfer/payment endpoints are used.
 */
@Service
public class PlaidToolService {

    private static final Logger log = LoggerFactory.getLogger(PlaidToolService.class);

    private final PlaidApi plaidApi;
    private final ClientRepository clientRepository;

    public PlaidToolService(PlaidApi plaidApi, ClientRepository clientRepository) {
        this.plaidApi = plaidApi;
        this.clientRepository = clientRepository;
    }

    // ── getAccounts ────────────────────────────────────────────────────────────

    public ToolResult getAccounts(String clientId) {
        String token = resolveToken(clientId);
        if (token == null) return ToolResult.fail("getAccounts", clientId, "No Plaid access token for client " + clientId);

        try {
            log.debug("[tool] getAccounts clientId={}", clientId);
            AccountsGetRequest req = new AccountsGetRequest().accessToken(token);
            Response<AccountsGetResponse> resp = plaidApi.accountsGet(req).execute();
            assertOk(resp, "accountsGet");

            List<Map<String, Object>> accounts = resp.body().getAccounts().stream()
                    .map(a -> {
                        // Explicit Map<String,Object> so stream infers the right type
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("accountId",        a.getAccountId());
                        m.put("name",             a.getName());
                        m.put("type",             a.getType() != null ? a.getType().getValue() : null);
                        m.put("subtype",          a.getSubtype() != null ? a.getSubtype().getValue() : null);
                        m.put("currentBalance",   a.getBalances().getCurrent());
                        m.put("availableBalance", a.getBalances().getAvailable());
                        m.put("currencyCode",     a.getBalances().getIsoCurrencyCode());
                        return m;
                    })
                    .toList();

            log.info("[tool] getAccounts clientId={} → {} accounts", clientId, accounts.size());
            return ToolResult.ok("getAccounts", clientId, Map.of("accounts", accounts));

        } catch (Exception e) {
            log.error("[tool] getAccounts failed for {}: {}", clientId, e.getMessage(), e);
            return ToolResult.fail("getAccounts", clientId, e.getMessage());
        }
    }

    // ── getTransactions ────────────────────────────────────────────────────────

    public ToolResult getTransactions(String clientId, LocalDate since) {
        String token = resolveToken(clientId);
        if (token == null) return ToolResult.fail("getTransactions", clientId, "No Plaid access token for client " + clientId);

        try {
            log.debug("[tool] getTransactions clientId={} since={}", clientId, since);

            List<Map<String, Object>> allTxns = new ArrayList<>();
            String cursor = null;
            boolean hasMore = true;

            while (hasMore) {
                TransactionsSyncRequest req = new TransactionsSyncRequest().accessToken(token);
                if (cursor != null) req.cursor(cursor);

                Response<TransactionsSyncResponse> resp = plaidApi.transactionsSync(req).execute();
                assertOk(resp, "transactionsSync");

                TransactionsSyncResponse body = resp.body();
                cursor = body.getNextCursor();
                hasMore = Boolean.TRUE.equals(body.getHasMore());

                body.getAdded().stream()
                        .filter(t -> since == null || !t.getDate().isBefore(since))
                        .forEach(t -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("transactionId", t.getTransactionId());
                            m.put("accountId",     t.getAccountId());
                            m.put("amount",        t.getAmount());
                            m.put("date",          t.getDate().toString());
                            m.put("name",          t.getName() != null ? t.getName() : "");
                            m.put("merchantName",  t.getMerchantName() != null ? t.getMerchantName() : "");
                            m.put("category",      t.getCategory() != null ? t.getCategory() : List.of());
                            m.put("pending",       Boolean.TRUE.equals(t.getPending()));
                            allTxns.add(m);
                        });
            }

            log.info("[tool] getTransactions clientId={} since={} → {} txns", clientId, since, allTxns.size());
            return ToolResult.ok("getTransactions", clientId,
                    Map.of("since", since != null ? since.toString() : "all", "transactions", allTxns));

        } catch (Exception e) {
            log.error("[tool] getTransactions failed for {}: {}", clientId, e.getMessage(), e);
            return ToolResult.fail("getTransactions", clientId, e.getMessage());
        }
    }

    // ── getHoldings ────────────────────────────────────────────────────────────

    public ToolResult getHoldings(String clientId) {
        String token = resolveToken(clientId);
        if (token == null) return ToolResult.fail("getHoldings", clientId, "No Plaid access token for client " + clientId);

        try {
            log.debug("[tool] getHoldings clientId={}", clientId);
            InvestmentsHoldingsGetRequest req = new InvestmentsHoldingsGetRequest().accessToken(token);
            Response<InvestmentsHoldingsGetResponse> resp = plaidApi.investmentsHoldingsGet(req).execute();
            assertOk(resp, "investmentsHoldingsGet");

            List<Map<String, Object>> holdings = resp.body().getHoldings().stream()
                    .map(h -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("accountId",        h.getAccountId());
                        m.put("securityId",       h.getSecurityId());
                        m.put("quantity",         h.getQuantity());
                        m.put("institutionValue", h.getInstitutionValue());
                        m.put("costBasis",        h.getCostBasis() != null ? h.getCostBasis() : 0.0);
                        return m;
                    })
                    .toList();

            List<Map<String, Object>> securities = resp.body().getSecurities().stream()
                    .map(s -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("securityId",   s.getSecurityId());
                        m.put("name",         s.getName() != null ? s.getName() : "");
                        m.put("tickerSymbol", s.getTickerSymbol() != null ? s.getTickerSymbol() : "");
                        m.put("type",         s.getType() != null ? s.getType() : "");
                        return m;
                    })
                    .toList();

            log.info("[tool] getHoldings clientId={} → {} holdings", clientId, holdings.size());
            return ToolResult.ok("getHoldings", clientId, Map.of("holdings", holdings, "securities", securities));

        } catch (Exception e) {
            log.error("[tool] getHoldings failed for {}: {}", clientId, e.getMessage(), e);
            return ToolResult.fail("getHoldings", clientId, e.getMessage());
        }
    }

    // ── getLiabilities ────────────────────────────────────────────────────────

    public ToolResult getLiabilities(String clientId) {
        String token = resolveToken(clientId);
        if (token == null) return ToolResult.fail("getLiabilities", clientId, "No Plaid access token for client " + clientId);

        try {
            log.debug("[tool] getLiabilities clientId={}", clientId);
            LiabilitiesGetRequest req = new LiabilitiesGetRequest().accessToken(token);
            Response<LiabilitiesGetResponse> resp = plaidApi.liabilitiesGet(req).execute();
            assertOk(resp, "liabilitiesGet");

            LiabilitiesObject liabilities = resp.body().getLiabilities();

            List<Map<String, Object>> creditCards = liabilities.getCredit() != null
                    ? liabilities.getCredit().stream()
                        .map(c -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("accountId",         c.getAccountId());
                            m.put("lastPaymentAmount", c.getLastPaymentAmount() != null ? c.getLastPaymentAmount() : 0.0);
                            m.put("minimumPayment",    c.getMinimumPaymentAmount() != null ? c.getMinimumPaymentAmount() : 0.0);
                            m.put("nextPaymentDue",    c.getNextPaymentDueDate() != null ? c.getNextPaymentDueDate().toString() : "");
                            return m;
                        })
                        .toList()
                    : List.of();

            List<Map<String, Object>> mortgages = liabilities.getMortgage() != null
                    ? liabilities.getMortgage().stream()
                        .map(mo -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("accountId",            mo.getAccountId());
                            m.put("originationPrincipal", mo.getOriginationPrincipalAmount() != null ? mo.getOriginationPrincipalAmount() : 0.0);
                            m.put("nextPaymentDue",       mo.getNextPaymentDueDate() != null ? mo.getNextPaymentDueDate().toString() : "");
                            m.put("interestRate",         mo.getInterestRate() != null ? mo.getInterestRate().getPercentage() : null);
                            return m;
                        })
                        .toList()
                    : List.of();

            List<Map<String, Object>> studentLoans = liabilities.getStudent() != null
                    ? liabilities.getStudent().stream()
                        .map(s -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("accountId",          s.getAccountId());
                            m.put("outstandingBalance", s.getOutstandingInterestAmount() != null ? s.getOutstandingInterestAmount() : 0.0);
                            m.put("nextPaymentDue",     s.getNextPaymentDueDate() != null ? s.getNextPaymentDueDate().toString() : "");
                            return m;
                        })
                        .toList()
                    : List.of();

            log.info("[tool] getLiabilities clientId={} → credit={} mortgage={} student={}",
                    clientId, creditCards.size(), mortgages.size(), studentLoans.size());
            return ToolResult.ok("getLiabilities", clientId,
                    Map.of("creditCards", creditCards, "mortgages", mortgages, "studentLoans", studentLoans));

        } catch (Exception e) {
            log.error("[tool] getLiabilities failed for {}: {}", clientId, e.getMessage(), e);
            return ToolResult.fail("getLiabilities", clientId, e.getMessage());
        }
    }

    // ── getIdentity ───────────────────────────────────────────────────────────

    public ToolResult getIdentity(String clientId) {
        String token = resolveToken(clientId);
        if (token == null) return ToolResult.fail("getIdentity", clientId, "No Plaid access token for client " + clientId);

        try {
            log.debug("[tool] getIdentity clientId={}", clientId);
            IdentityGetRequest req = new IdentityGetRequest().accessToken(token);
            Response<IdentityGetResponse> resp = plaidApi.identityGet(req).execute();
            assertOk(resp, "identityGet");

            List<Map<String, Object>> accounts = resp.body().getAccounts().stream()
                    .map(a -> {
                        List<String> names = a.getOwners().stream()
                                .flatMap(o -> o.getNames().stream())
                                .toList();
                        List<String> emails = a.getOwners().stream()
                                .flatMap(o -> o.getEmails().stream().map(Email::getData))
                                .toList();
                        List<String> phones = a.getOwners().stream()
                                .flatMap(o -> o.getPhoneNumbers().stream().map(PhoneNumber::getData))
                                .toList();
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("accountId", a.getAccountId());
                        m.put("names",     names);
                        m.put("emails",    emails);
                        m.put("phones",    phones);
                        return m;
                    })
                    .toList();

            log.info("[tool] getIdentity clientId={} → {} account owners", clientId, accounts.size());
            return ToolResult.ok("getIdentity", clientId, Map.of("accounts", accounts));

        } catch (Exception e) {
            log.error("[tool] getIdentity failed for {}: {}", clientId, e.getMessage(), e);
            return ToolResult.fail("getIdentity", clientId, e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String resolveToken(String clientId) {
        return clientRepository.findById(clientId)
                .map(c -> c.getPlaidAccessToken())
                .orElse(null);
    }

    private static <T> void assertOk(Response<T> resp, String op) throws IOException {
        if (!resp.isSuccessful() || resp.body() == null) {
            String body = resp.errorBody() != null ? resp.errorBody().string() : "(no body)";
            throw new RuntimeException("Plaid " + op + " [HTTP " + resp.code() + "]: " + body);
        }
    }
}
