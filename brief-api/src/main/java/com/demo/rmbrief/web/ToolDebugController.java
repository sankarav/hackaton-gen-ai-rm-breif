package com.demo.rmbrief.web;

import com.demo.rmbrief.delta.ChangeReport;
import com.demo.rmbrief.delta.DeltaService;
import com.demo.rmbrief.tools.CrmContextService;
import com.demo.rmbrief.tools.PlaidToolService;
import com.demo.rmbrief.tools.ToolResult;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Dev-only endpoints for verifying tools and delta computation against Plaid Sandbox.
 */
@RestController
public class ToolDebugController {

    private final PlaidToolService plaidTools;
    private final CrmContextService crmContext;
    private final DeltaService deltaService;

    public ToolDebugController(PlaidToolService plaidTools, CrmContextService crmContext, DeltaService deltaService) {
        this.plaidTools = plaidTools;
        this.crmContext = crmContext;
        this.deltaService = deltaService;
    }

    @GetMapping("/debug/tools/{clientId}/{tool}")
    public ToolResult runTool(
            @PathVariable String clientId,
            @PathVariable String tool,
            @RequestParam(required = false) String since) {

        LocalDate sinceDate = since != null ? LocalDate.parse(since) : null;

        return switch (tool) {
            case "getAccounts"     -> plaidTools.getAccounts(clientId);
            case "getTransactions" -> plaidTools.getTransactions(clientId, sinceDate);
            case "getHoldings"     -> plaidTools.getHoldings(clientId);
            case "getLiabilities"  -> plaidTools.getLiabilities(clientId);
            case "getIdentity"     -> plaidTools.getIdentity(clientId);
            case "getCrmContext"   -> crmContext.getCrmContext(clientId);
            default                -> ToolResult.fail(tool, clientId, "Unknown tool: " + tool);
        };
    }

    /** Run the full deterministic delta computation and return the ChangeReport. */
    @GetMapping("/debug/delta/{clientId}")
    public ChangeReport runDelta(@PathVariable String clientId) {
        return deltaService.compute(clientId);
    }
}
