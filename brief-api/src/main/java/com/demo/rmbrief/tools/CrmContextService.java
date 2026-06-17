package com.demo.rmbrief.tools;

import com.demo.rmbrief.crm.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * getCrmContext tool — reads the synthetic CRM (Postgres) for a client.
 * Returns last_meeting_date, notes, open promises, segment, relationship tenure,
 * and any synthetic products (CDs, term deposits) not modelled by Plaid.
 */
@Service
public class CrmContextService {

    private static final Logger log = LoggerFactory.getLogger(CrmContextService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ClientRepository clientRepository;
    private final InteractionRepository interactionRepository;
    private final SyntheticProductRepository syntheticProductRepository;

    public CrmContextService(ClientRepository clientRepository,
                             InteractionRepository interactionRepository,
                             SyntheticProductRepository syntheticProductRepository) {
        this.clientRepository = clientRepository;
        this.interactionRepository = interactionRepository;
        this.syntheticProductRepository = syntheticProductRepository;
    }

    public ToolResult getCrmContext(String clientId) {
        log.debug("[tool] getCrmContext clientId={}", clientId);

        var client = clientRepository.findById(clientId).orElse(null);
        if (client == null) return ToolResult.fail("getCrmContext", clientId, "Client not found: " + clientId);

        var lastInteraction = interactionRepository
                .findTopByClientClientIdOrderByMeetingDateDesc(clientId)
                .orElse(null);

        List<Map<String, Object>> products = syntheticProductRepository
                .findByClientClientId(clientId)
                .stream()
                .map(p -> Map.<String, Object>of(
                        "productType",  p.getProductType(),
                        "balance",      p.getBalance(),
                        "maturityDate", p.getMaturityDate() != null ? p.getMaturityDate().toString() : null,
                        "rate",         p.getRate()
                ))
                .toList();

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("clientId",          client.getClientId());
        data.put("fullName",          client.getFullName());
        data.put("segment",           client.getSegment());
        data.put("rmName",            client.getRmName());
        data.put("relationshipStart", client.getRelationshipStart() != null
                ? client.getRelationshipStart().toString() : null);

        if (lastInteraction != null) {
            data.put("lastMeetingDate",  lastInteraction.getMeetingDate().toString());
            data.put("lastMeetingNotes", lastInteraction.getNotes());
            data.put("openPromises",     parsePromises(lastInteraction.getPromises()));
        } else {
            data.put("lastMeetingDate",  null);
            data.put("lastMeetingNotes", null);
            data.put("openPromises",     List.of());
        }

        data.put("syntheticProducts", products);

        log.info("[tool] getCrmContext clientId={} lastMeeting={} products={}",
                clientId,
                lastInteraction != null ? lastInteraction.getMeetingDate() : "none",
                products.size());

        return ToolResult.ok("getCrmContext", clientId, data);
    }

    private List<String> parsePromises(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Could not parse promises JSON: {}", json);
            return List.of(json);
        }
    }
}
