package com.demo.rmbrief.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.*;
import com.demo.rmbrief.delta.ChangeReport;
import com.demo.rmbrief.tools.CrmContextService;
import com.demo.rmbrief.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Makes the final Claude synthesis call.
 *
 * Invariants enforced:
 * - Claude receives ONLY the structured ChangeReport + CRM context. No free-form data.
 * - The prompt explicitly forbids inventing numbers not present in the input.
 * - Every citation in the returned brief is validated against the set of real IDs
 *   (accountId, transactionId) from the ChangeReport. Unresolvable citations are stripped.
 */
@Service
public class SynthesisService {

    private static final Logger log = LoggerFactory.getLogger(SynthesisService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String SYSTEM_PROMPT = """
            You are a bank Relationship Manager AI assistant generating a pre-meeting client brief.

            STRICT RULES — violating any rule makes the output unusable:
            1. Use ONLY the facts provided in the structured data below. Never invent numbers, \
            dates, account names, or transaction details.
            2. Every item in changedSinceLastMeeting, watchOuts, and opportunities MUST include \
            at least one citation drawn from the actual accountId or transactionId values present \
            in the ChangeReport. Do not fabricate citation IDs.
            3. talkingPoints must be grounded in specific data points — cite the source in the \
            talking point text (e.g. "the $5,850 transfer on 2026-06-12").
            4. Respond with ONLY a valid JSON object. No markdown fences, no explanation, \
            no text outside the JSON.
            5. If data is insufficient for a section, write an honest brief statement rather \
            than guessing.

            Output schema (respond with exactly this structure):
            {
              "clientId": "string",
              "snapshot": {
                "name": "string",
                "segment": "string",
                "tenureYears": 0,
                "totalRelationshipValue": 0
              },
              "changedSinceLastMeeting": [
                { "summary": "string", "citations": ["accountId or transactionId"] }
              ],
              "watchOuts": [
                { "summary": "string", "severity": "high|med|low", "citations": ["id"] }
              ],
              "opportunities": [
                { "summary": "string", "citations": ["id"] }
              ],
              "talkingPoints": ["string", "string", "string"],
              "lastInteractionRecap": {
                "date": "YYYY-MM-DD",
                "summary": "string",
                "openPromises": ["string"]
              }
            }
            """;

    private final CrmContextService crmContextService;
    private final AnthropicClient anthropic;

    public SynthesisService(CrmContextService crmContextService) {
        this.crmContextService = crmContextService;
        this.anthropic = AnthropicOkHttpClient.fromEnv();
    }

    public BriefSchema synthesize(String clientId, ChangeReport report) {
        log.info("[synthesis] starting for clientId={}", clientId);

        // Fetch CRM context for the synthesis prompt
        ToolResult crmResult = crmContextService.getCrmContext(clientId);
        String crmJson;
        String changeReportJson;
        try {
            crmJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(crmResult.data());
            changeReportJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize input data for synthesis", e);
        }

        String userMessage = String.format("""
                Generate a pre-meeting brief for the following client.

                === CRM CONTEXT ===
                %s

                === CHANGE REPORT (since last meeting %s) ===
                %s

                Respond with ONLY the JSON brief object. No text outside the JSON.
                """, crmJson, report.lastMeetingDate(), changeReportJson);

        log.debug("[synthesis] sending to Claude, changeReport size={} chars", changeReportJson.length());

        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of("claude-sonnet-4-6"))
                .maxTokens(4096L)
                .system(SYSTEM_PROMPT)
                .addUserMessage(userMessage)
                .build();

        Message response = anthropic.messages().create(params);

        String rawJson = response.content().stream()
                .filter(ContentBlock::isText)
                .map(b -> b.asText().text())
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Claude returned no text content"));

        log.debug("[synthesis] received {} chars from Claude", rawJson.length());

        // Strip markdown fences if Claude added them despite instructions
        rawJson = stripMarkdownFences(rawJson);

        BriefSchema brief;
        try {
            brief = MAPPER.readValue(rawJson, BriefSchema.class);
        } catch (Exception e) {
            log.error("[synthesis] JSON parse failed. Raw response:\n{}", rawJson);
            throw new RuntimeException("Claude response was not valid BriefSchema JSON: " + e.getMessage(), e);
        }

        // Validate and strip citations that don't resolve to real IDs
        Set<String> validIds = buildValidIdSet(report);
        brief = validateCitations(brief, validIds);

        log.info("[synthesis] complete for clientId={} watchOuts={} opportunities={} talkingPoints={}",
                clientId,
                brief.watchOuts().size(),
                brief.opportunities().size(),
                brief.talkingPoints().size());

        return brief;
    }

    // ── citation validation ───────────────────────────────────────────────────

    private Set<String> buildValidIdSet(ChangeReport report) {
        Set<String> ids = new HashSet<>();
        report.accountSummaries().forEach(a -> ids.add(a.accountId()));
        report.largeOutflows().forEach(o -> {
            ids.add(o.transactionId());
            ids.add(o.accountId());
        });
        // Maturity alerts have no Plaid ID — allow productType as a citation key
        report.maturityAlerts().forEach(m -> ids.add(m.productType()));
        log.debug("[synthesis] valid citation IDs: {}", ids);
        return ids;
    }

    private BriefSchema validateCitations(BriefSchema brief, Set<String> validIds) {
        int stripped = 0;

        List<BriefSchema.Section> changed = new ArrayList<>();
        for (BriefSchema.Section s : brief.changedSinceLastMeeting()) {
            var result = filterCitations(s.citations(), validIds);
            stripped += result.stripped();
            changed.add(new BriefSchema.Section(s.summary(), result.valid()));
        }

        List<BriefSchema.WatchOut> watchOuts = new ArrayList<>();
        for (BriefSchema.WatchOut w : brief.watchOuts()) {
            var result = filterCitations(w.citations(), validIds);
            stripped += result.stripped();
            watchOuts.add(new BriefSchema.WatchOut(w.summary(), w.severity(), result.valid()));
        }

        List<BriefSchema.Section> opps = new ArrayList<>();
        for (BriefSchema.Section s : brief.opportunities()) {
            var result = filterCitations(s.citations(), validIds);
            stripped += result.stripped();
            opps.add(new BriefSchema.Section(s.summary(), result.valid()));
        }

        if (stripped > 0) {
            log.warn("[synthesis] stripped {} unresolvable citations", stripped);
        }

        return new BriefSchema(
                brief.clientId(),
                brief.snapshot(),
                changed,
                watchOuts,
                opps,
                brief.talkingPoints(),
                brief.lastInteractionRecap()
        );
    }

    private record FilterResult(List<String> valid, int stripped) {}

    private FilterResult filterCitations(List<String> citations, Set<String> validIds) {
        if (citations == null || citations.isEmpty()) return new FilterResult(List.of(), 0);
        List<String> valid = citations.stream().filter(validIds::contains).toList();
        return new FilterResult(valid, citations.size() - valid.size());
    }

    // ── utilities ─────────────────────────────────────────────────────────────

    private static String stripMarkdownFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) trimmed = trimmed.substring(firstNewline + 1);
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).trim();
        }
        return trimmed;
    }
}
