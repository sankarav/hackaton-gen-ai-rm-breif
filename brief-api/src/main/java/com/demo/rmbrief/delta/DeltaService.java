package com.demo.rmbrief.delta;

import com.demo.rmbrief.tools.CrmContextService;
import com.demo.rmbrief.tools.PlaidToolService;
import com.demo.rmbrief.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic delta computation — no LLM involved.
 *
 * Reads current Plaid data + CRM context, compares against last_meeting_date,
 * and produces a ChangeReport with grounded citations. The LLM in the synthesis
 * step is only allowed to write prose from this report.
 */
@Service
public class DeltaService {

    private static final Logger log = LoggerFactory.getLogger(DeltaService.class);

    /** Alert window for maturing products (days). */
    private static final int MATURITY_ALERT_DAYS = 60;

    @Value("${app.delta.large-outflow-threshold:10000}")
    private double largeOutflowThreshold;

    @Value("${app.delta.dormancy-days:90}")
    private int dormancyDays;

    private final PlaidToolService plaidTools;
    private final CrmContextService crmContextService;

    public DeltaService(PlaidToolService plaidTools, CrmContextService crmContextService) {
        this.plaidTools = plaidTools;
        this.crmContextService = crmContextService;
    }

    /**
     * Main entry point. Fetches all data and computes the ChangeReport.
     */
    public ChangeReport compute(String clientId) {
        log.info("[delta] computing ChangeReport for clientId={}", clientId);

        // ── 1. CRM context (last meeting date, synthetic products) ─────────────
        ToolResult crmResult = crmContextService.getCrmContext(clientId);
        Map<String, Object> crm = asMap(crmResult.data());

        LocalDate lastMeetingDate = parseDate(crm.get("lastMeetingDate"));
        LocalDate today = LocalDate.now();
        log.debug("[delta] lastMeetingDate={}", lastMeetingDate);

        // ── 2. Accounts (current balances) ────────────────────────────────────
        ToolResult accountsResult = plaidTools.getAccounts(clientId);
        List<Map<String, Object>> rawAccounts = asList(asMap(accountsResult.data()).get("accounts"));

        List<ChangeReport.AccountSummary> accountSummaries = rawAccounts.stream()
                .map(a -> new ChangeReport.AccountSummary(
                        str(a.get("accountId")),
                        str(a.get("name")),
                        str(a.get("type")),
                        str(a.get("subtype")),
                        toDouble(a.get("currentBalance")),
                        toDouble(a.get("availableBalance")),
                        str(a.get("currencyCode"))
                ))
                .toList();

        // Total relationship value = depository + investment balances
        double totalRelationshipValue = accountSummaries.stream()
                .filter(a -> "depository".equals(a.type()) || "investment".equals(a.type()))
                .mapToDouble(a -> a.currentBalance() != null ? a.currentBalance() : 0.0)
                .sum();

        log.debug("[delta] {} accounts, totalRelationshipValue={}", accountSummaries.size(), totalRelationshipValue);

        // ── 3. Transactions since last meeting ───────────────────────────────
        ToolResult txnResult = plaidTools.getTransactions(clientId, lastMeetingDate);
        List<Map<String, Object>> txns = asList(asMap(txnResult.data()).get("transactions"));

        // ── 4. Large outflows (positive amount = debit/outflow in Plaid model) ─
        List<ChangeReport.LargeOutflow> largeOutflows = txns.stream()
                .filter(t -> toDouble(t.get("amount")) >= largeOutflowThreshold)
                .map(t -> new ChangeReport.LargeOutflow(
                        str(t.get("transactionId")),
                        str(t.get("accountId")),
                        toDouble(t.get("amount")),
                        str(t.get("date")),
                        str(t.get("name"))
                ))
                .toList();

        log.info("[delta] {} txns since lastMeeting, {} large outflows (threshold=${})",
                txns.size(), largeOutflows.size(), largeOutflowThreshold);

        // ── 5. Dormancy check ─────────────────────────────────────────────────
        // Get ALL transactions (no since filter) to find the most recent one
        ToolResult allTxnResult = plaidTools.getTransactions(clientId, null);
        List<Map<String, Object>> allTxns = asList(asMap(allTxnResult.data()).get("transactions"));

        LocalDate mostRecentTxnDate = allTxns.stream()
                .map(t -> parseDate(t.get("date")))
                .filter(d -> d != null)
                .max(LocalDate::compareTo)
                .orElse(null);

        int daysSinceLastTxn = mostRecentTxnDate != null
                ? (int) ChronoUnit.DAYS.between(mostRecentTxnDate, today)
                : Integer.MAX_VALUE;
        boolean dormant = daysSinceLastTxn >= dormancyDays;

        log.debug("[delta] mostRecentTxn={} daysSince={} dormant={}", mostRecentTxnDate, daysSinceLastTxn, dormant);

        // ── 6. Maturity alerts (synthetic products only — Plaid doesn't expose CD maturity) ─
        List<Map<String, Object>> syntheticProducts = asList(crm.get("syntheticProducts"));

        List<ChangeReport.MaturityAlert> maturityAlerts = new ArrayList<>();
        for (Map<String, Object> p : syntheticProducts) {
            LocalDate maturityDate = parseDate(p.get("maturityDate"));
            if (maturityDate == null) continue;
            int daysUntil = (int) ChronoUnit.DAYS.between(today, maturityDate);
            if (daysUntil <= MATURITY_ALERT_DAYS) {
                maturityAlerts.add(new ChangeReport.MaturityAlert(
                        str(p.get("productType")),
                        toDouble(p.get("balance")),
                        str(p.get("maturityDate")),
                        daysUntil,
                        toDouble(p.get("rate"))
                ));
                log.info("[delta] maturity alert: {} balance={} matures={} ({} days)",
                        p.get("productType"), p.get("balance"), maturityDate, daysUntil);
            }
        }

        ChangeReport report = new ChangeReport(
                clientId,
                lastMeetingDate,
                today,
                accountSummaries,
                totalRelationshipValue,
                largeOutflows,
                txns.size(),
                dormant,
                daysSinceLastTxn,
                maturityAlerts
        );

        log.info("[delta] ChangeReport complete for {}: outflows={} maturityAlerts={} dormant={}",
                clientId, largeOutflows.size(), maturityAlerts.size(), dormant);
        return report;
    }

    // ── safe extraction helpers ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object o) {
        if (o instanceof List<?> l) return (List<Map<String, Object>>) l;
        return List.of();
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }

    private static LocalDate parseDate(Object o) {
        if (o == null) return null;
        try { return LocalDate.parse(o.toString()); } catch (Exception e) { return null; }
    }
}
