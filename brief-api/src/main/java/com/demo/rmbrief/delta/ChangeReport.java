package com.demo.rmbrief.delta;

import java.time.LocalDate;
import java.util.List;

/**
 * Deterministic summary of what changed since last meeting.
 * Produced by DeltaService from raw Plaid + CRM data — never by the LLM.
 * Every item carries a citation (transactionId or accountId) so the synthesis
 * prompt can require the LLM to reference only ids that appear here.
 */
public record ChangeReport(
        String clientId,
        LocalDate lastMeetingDate,
        LocalDate computedAt,

        // Current account snapshot
        List<AccountSummary> accountSummaries,
        double totalRelationshipValue,

        // What changed since last meeting
        List<LargeOutflow> largeOutflows,
        int transactionCountSinceLastMeeting,

        // Risk flags
        boolean dormant,
        int daysSinceLastTransaction,

        // Opportunities
        List<MaturityAlert> maturityAlerts
) {

    /** One account with its current balance. Citation = accountId. */
    public record AccountSummary(
            String accountId,
            String name,
            String type,
            String subtype,
            Double currentBalance,
            Double availableBalance,
            String currencyCode
    ) {}

    /**
     * A transaction whose absolute amount exceeded the configured threshold.
     * Citation = transactionId.
     */
    public record LargeOutflow(
            String transactionId,
            String accountId,
            double amount,
            String date,
            String description
    ) {}

    /**
     * A synthetic product (CD, term deposit) maturing within the alert window.
     * Citation = productType + maturityDate (no Plaid id for synthetic products).
     */
    public record MaturityAlert(
            String productType,
            double balance,
            String maturityDate,
            int daysUntilMaturity,
            Double rate
    ) {}
}
