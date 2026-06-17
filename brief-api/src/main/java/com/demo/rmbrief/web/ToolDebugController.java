package com.demo.rmbrief.web;

import com.demo.rmbrief.tools.CrmContextService;
import com.demo.rmbrief.tools.PlaidToolService;
import com.demo.rmbrief.tools.ToolResult;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Dev-only endpoint for verifying each tool against the Plaid Sandbox.
 * Usage: GET /debug/tools/{clientId}/{toolName}?since=YYYY-MM-DD
 */
@RestController
@RequestMapping("/debug/tools")
public class ToolDebugController {

    private final PlaidToolService plaidTools;
    private final CrmContextService crmContext;

    public ToolDebugController(PlaidToolService plaidTools, CrmContextService crmContext) {
        this.plaidTools = plaidTools;
        this.crmContext = crmContext;
    }

    @GetMapping("/{clientId}/{tool}")
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
}
