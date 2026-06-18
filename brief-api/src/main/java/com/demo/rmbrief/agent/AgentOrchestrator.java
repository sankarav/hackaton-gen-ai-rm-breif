package com.demo.rmbrief.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.demo.rmbrief.delta.ChangeReport;
import com.demo.rmbrief.delta.DeltaService;
import com.demo.rmbrief.llm.BriefSchema;
import com.demo.rmbrief.llm.SynthesisService;
import com.demo.rmbrief.tools.CrmContextService;
import com.demo.rmbrief.tools.PlaidToolService;
import com.demo.rmbrief.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Drives the agentic gather loop: plan → call tools → compute delta.
 * In eager mode all 6 tools are called upfront (no LLM planning round-trip).
 * In LLM mode Claude selects and sequences tool calls itself.
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${app.agent.eager-tool-calls:true}")
    private boolean eagerToolCalls;

    private final PlaidToolService plaidTools;
    private final CrmContextService crmContext;
    private final DeltaService deltaService;
    private final SynthesisService synthesisService;
    private final AnthropicClient anthropic;

    public AgentOrchestrator(PlaidToolService plaidTools, CrmContextService crmContext,
                             DeltaService deltaService, SynthesisService synthesisService) {
        this.plaidTools = plaidTools;
        this.crmContext = crmContext;
        this.deltaService = deltaService;
        this.synthesisService = synthesisService;
        this.anthropic = AnthropicOkHttpClient.fromEnv();
    }

    // ── public entry point ─────────────────────────────────────────────────────

    public void run(String clientId, StepEmitter emitter) {
        try {
            emitter.step("agent_start", "Starting brief generation for client " + clientId);
            log.info("[agent] starting gather for clientId={} eager={}", clientId, eagerToolCalls);

            if (eagerToolCalls) {
                runEager(clientId, emitter);
            } else {
                runLlmDriven(clientId, emitter);
            }

            // Deterministic delta — never the LLM
            emitter.step("delta_start", "Computing deterministic change report since last meeting…");
            ChangeReport report = deltaService.compute(clientId);
            emitter.delta(report);

            String deltaSummary = String.format(
                "Delta complete — %d accounts, TRV $%.0f, %d large outflows, %d maturity alerts, dormant=%s",
                report.accountSummaries().size(),
                report.totalRelationshipValue(),
                report.largeOutflows().size(),
                report.maturityAlerts().size(),
                report.dormant()
            );
            emitter.step("delta_done", deltaSummary);
            log.info("[agent] {}", deltaSummary);

            emitter.step("gather_complete", "Data gather complete — starting synthesis…");

            // Final Claude synthesis call — prose from structured facts only
            emitter.step("synthesis_start", "Claude synthesizing brief from ChangeReport…");
            BriefSchema brief = synthesisService.synthesize(clientId, report);
            emitter.brief(brief);
            emitter.step("synthesis_done", "Brief generated with " +
                brief.watchOuts().size() + " watch-outs, " +
                brief.opportunities().size() + " opportunities, " +
                brief.talkingPoints().size() + " talking points.");
            emitter.complete();

        } catch (Exception e) {
            log.error("[agent] gather failed for {}", clientId, e);
            emitter.error(e.getMessage());
        }
    }

    // ── eager mode: call all tools upfront ────────────────────────────────────

    private void runEager(String clientId, StepEmitter emitter) {
        String[] tools = {"getCrmContext", "getAccounts", "getTransactions", "getHoldings", "getLiabilities", "getIdentity"};

        for (String toolName : tools) {
            emitter.step("tool_call", "Fetching " + toolLabel(toolName) + "…");
            ToolResult result = callTool(toolName, clientId);

            if (result.success()) {
                emitter.step("tool_result", summarize(toolName, result));
                log.info("[agent] eager tool={} ok", toolName);
            } else {
                emitter.step("tool_error", toolName + " failed: " + result.error());
                log.warn("[agent] eager tool={} error={}", toolName, result.error());
            }
        }
    }

    // ── LLM-driven mode: Claude decides which tools to call ───────────────────

    private void runLlmDriven(String clientId, StepEmitter emitter) {
        List<ToolUnion> toolDefs = buildToolDefinitions();

        String systemPrompt = """
            You are an AI assistant helping a bank Relationship Manager prepare for a client meeting.
            Gather all necessary financial data using the provided tools. Always start with \
            getCrmContext to learn the client's last meeting date, then call the Plaid tools \
            (getAccounts, getTransactions, getHoldings, getLiabilities, getIdentity).
            Be thorough: call every tool. Pass clientId exactly as given — do not modify it.
            """;

        MessageCreateParams params = MessageCreateParams.builder()
            .model(Model.of("claude-sonnet-4-6"))
            .maxTokens(4096L)
            .system(systemPrompt)
            .addUserMessage("Gather all financial data for client ID: " + clientId)
            .tools(toolDefs)
            .build();

        int maxRounds = 10;
        for (int round = 0; round < maxRounds; round++) {
            emitter.step("llm_plan", round == 0 ? "Claude planning tool calls…" : "Claude processing results…");
            log.debug("[agent] llm round={}", round);

            Message response = anthropic.messages().create(params);

            String stopReason = response.stopReason().map(Object::toString).orElse("end_turn");
            log.debug("[agent] llm stopReason={} blocks={}", stopReason, response.content().size());

            if (!"tool_use".equals(stopReason)) {
                String finalText = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(b -> b.asText().text())
                    .findFirst().orElse("");
                if (!finalText.isBlank()) {
                    emitter.step("llm_summary", finalText);
                }
                break;
            }

            // Execute tool calls
            List<ContentBlockParam> toolResults = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                if (!block.isToolUse()) continue;

                ToolUseBlock toolUse = block.asToolUse();
                String toolName = toolUse.name();
                String toolUseId = toolUse.id();

                emitter.step("tool_call", "Claude calling: " + toolLabel(toolName) + "…");
                log.info("[agent] llm tool_call name={} id={}", toolName, toolUseId);

                ToolResult result = callTool(toolName, clientId);

                String resultContent;
                try {
                    resultContent = result.success()
                        ? MAPPER.writeValueAsString(result.data())
                        : (result.error() != null ? result.error() : "Tool failed with no error message");
                } catch (Exception e) {
                    resultContent = "Serialization error: " + e.getMessage();
                }

                toolResults.add(ContentBlockParam.ofToolResult(
                    ToolResultBlockParam.builder()
                        .toolUseId(toolUseId)
                        .content(resultContent)
                        .isError(!result.success())
                        .build()
                ));

                if (result.success()) {
                    emitter.step("tool_result", summarize(toolName, result));
                } else {
                    emitter.step("tool_error", toolName + " failed: " + result.error());
                }
            }

            if (toolResults.isEmpty()) break;

            params = params.toBuilder()
                .addMessage(response)
                .addUserMessageOfBlockParams(toolResults)
                .build();
        }
    }

    // ── tool dispatch ─────────────────────────────────────────────────────────

    private ToolResult callTool(String toolName, String clientId) {
        return switch (toolName) {
            case "getCrmContext"   -> crmContext.getCrmContext(clientId);
            case "getAccounts"     -> plaidTools.getAccounts(clientId);
            case "getTransactions" -> plaidTools.getTransactions(clientId, null);
            case "getHoldings"     -> plaidTools.getHoldings(clientId);
            case "getLiabilities"  -> plaidTools.getLiabilities(clientId);
            case "getIdentity"     -> plaidTools.getIdentity(clientId);
            default                -> ToolResult.fail(toolName, clientId, "Unknown tool: " + toolName);
        };
    }

    // ── tool definitions for Claude ───────────────────────────────────────────

    private List<ToolUnion> buildToolDefinitions() {
        return List.of(
            ToolUnion.ofTool(tool("getCrmContext",
                "Retrieve CRM context for a bank client: last meeting date, notes, open promises, segment, relationship tenure, and synthetic products (CDs, term deposits).",
                "clientId", "The bank client ID (e.g. client_001)")),

            ToolUnion.ofTool(tool("getAccounts",
                "Retrieve all Plaid-linked bank accounts with current balances for the client.",
                "clientId", "The bank client ID")),

            ToolUnion.ofTool(tool("getTransactions",
                "Retrieve all transactions from Plaid for the client using the sync cursor (returns all available transactions).",
                "clientId", "The bank client ID")),

            ToolUnion.ofTool(tool("getHoldings",
                "Retrieve investment holdings and securities from Plaid for the client.",
                "clientId", "The bank client ID")),

            ToolUnion.ofTool(tool("getLiabilities",
                "Retrieve liabilities (credit cards, mortgages, student loans) from Plaid for the client.",
                "clientId", "The bank client ID")),

            ToolUnion.ofTool(tool("getIdentity",
                "Retrieve identity information (names, emails, phone numbers) from Plaid for the client.",
                "clientId", "The bank client ID"))
        );
    }

    private Tool tool(String name, String description, String paramName, String paramDescription) {
        return Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(Tool.InputSchema.builder()
                .type(JsonValue.from("object"))
                .properties(Tool.InputSchema.Properties.builder()
                    .putAdditionalProperty(paramName, JsonValue.from(Map.of(
                        "type", "string",
                        "description", paramDescription
                    )))
                    .build())
                .addRequired(paramName)
                .build())
            .build();
    }

    // ── human-readable helpers ────────────────────────────────────────────────

    private static String toolLabel(String toolName) {
        return switch (toolName) {
            case "getCrmContext"   -> "CRM context";
            case "getAccounts"    -> "account balances";
            case "getTransactions"-> "transaction history";
            case "getHoldings"    -> "investment holdings";
            case "getLiabilities" -> "liabilities";
            case "getIdentity"    -> "identity data";
            default               -> toolName;
        };
    }

    @SuppressWarnings("unchecked")
    private static String summarize(String toolName, ToolResult result) {
        try {
            Map<String, Object> data = (Map<String, Object>) result.data();
            return switch (toolName) {
                case "getCrmContext" -> String.format("CRM: %s | segment: %s | last meeting: %s",
                    data.get("fullName"), data.get("segment"), data.get("lastMeetingDate"));
                case "getAccounts" -> {
                    List<?> accts = (List<?>) data.get("accounts");
                    yield String.format("Accounts: %d found", accts != null ? accts.size() : 0);
                }
                case "getTransactions" -> {
                    List<?> txns = (List<?>) data.get("transactions");
                    yield String.format("Transactions: %d fetched", txns != null ? txns.size() : 0);
                }
                case "getHoldings" -> {
                    List<?> h = (List<?>) data.get("holdings");
                    yield String.format("Holdings: %d positions", h != null ? h.size() : 0);
                }
                case "getLiabilities" -> {
                    List<?> cc = (List<?>) data.get("creditCards");
                    List<?> mo = (List<?>) data.get("mortgages");
                    yield String.format("Liabilities: %d credit cards, %d mortgages",
                        cc != null ? cc.size() : 0, mo != null ? mo.size() : 0);
                }
                case "getIdentity" -> {
                    List<?> a = (List<?>) data.get("accounts");
                    yield String.format("Identity: %d accounts verified", a != null ? a.size() : 0);
                }
                default -> toolName + " completed";
            };
        } catch (Exception e) {
            return toolName + " completed";
        }
    }
}
