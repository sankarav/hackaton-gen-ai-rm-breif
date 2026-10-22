package com.demo.rmbrief.chat;

import com.demo.rmbrief.capture.AuditService;
import com.demo.rmbrief.crm.ClientRepository;
import com.demo.rmbrief.delta.ChangeReport;
import com.demo.rmbrief.llm.BriefSchema;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Saved briefs and their versions. Versions are immutable; an applied edit always creates a new one. */
@Service
public class BriefStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public record Saved(long briefId, int version) {}

    public record VersionInfo(int version, String createdBy, String changeNote, Instant createdAt) {}

    public record Summary(long briefId, Instant createdAt, int currentVersion) {}

    public record View(long briefId, String clientId, Instant createdAt, int version, int currentVersion, BriefSchema content,
                       ChangeReport changeReport, Map<String, ChatTools.Citation> citationDetails,
                       List<VersionInfo> versions) {}

    private final BriefRepository briefs;
    private final BriefVersionRepository versions;
    private final ClientRepository clients;
    private final ChatTools tools;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public BriefStore(BriefRepository briefs, BriefVersionRepository versions, ClientRepository clients,
                      ChatTools tools, AuditService audit, TransactionTemplate tx) {
        this.briefs = briefs;
        this.versions = versions;
        this.clients = clients;
        this.tools = tools;
        this.audit = audit;
        this.tx = tx;
    }

    public Saved create(String clientId, ChangeReport report, BriefSchema content) {
        return tx.execute(s -> {
            Brief b = new Brief();
            b.clientId = clientId;
            b.changeReport = json(report);
            b.currentVersion = 1;
            b.createdAt = Instant.now();
            b = briefs.save(b);
            versions.save(version(b.id, 1, content, Map.of(), "system", "Generated"));
            audit.record("system", "BRIEF_CREATED", clientId, null, "brief=" + b.id);
            return new Saved(b.id, 1);
        });
    }

    public List<Summary> recent(String clientId) {
        return briefs.findTop20ByClientIdOrderByCreatedAtDesc(clientId).stream()
                .map(b -> new Summary(b.id, b.createdAt, b.currentVersion)).toList();
    }

    public View view(long briefId, Integer version) {
        Brief b = find(briefId);
        int v = version == null ? b.currentVersion : version;
        BriefVersion bv = versions.findByBriefIdAndVersion(briefId, v)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Version not found"));
        List<VersionInfo> history = versions.findByBriefIdOrderByVersionDesc(briefId).stream()
                .map(x -> new VersionInfo(x.version, x.createdBy, x.changeNote, x.createdAt)).toList();
        return new View(b.id, b.clientId, b.createdAt, v, b.currentVersion, read(bv.content, BriefSchema.class),
                read(b.changeReport, ChangeReport.class), citations(bv.citationDetails), history);
    }

    /** Applies an RM-approved edit to the current version, re-checking every citation against live data. */
    public View apply(long briefId, int baseVersion, BriefEdit edit) {
        Brief b = find(briefId);
        if (b.currentVersion != baseVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The brief changed since this suggestion (now v" + b.currentVersion + ") — ask again");
        }
        View current = view(briefId, baseVersion);
        Set<String> reportIds = current.changeReport().citationIds();
        Map<String, ChatTools.Citation> live = edit.citations() == null || edit.citations().isEmpty()
                ? Map.of() : tools.liveCitations(b.clientId);

        Map<String, ChatTools.Citation> details = new LinkedHashMap<>(current.citationDetails());
        List<String> kept = edit.citations() == null ? List.of() : edit.citations().stream()
                .filter(id -> reportIds.contains(id) || details.containsKey(id) || live.containsKey(id))
                .distinct().toList();
        kept.stream().filter(id -> !reportIds.contains(id) && live.containsKey(id)).forEach(id -> details.put(id, live.get(id)));

        BriefEdit checked = edit.withCitations(kept);
        BriefSchema updated;
        try {
            updated = checked.applyTo(current.content());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        String actor = clients.findById(b.clientId).map(c -> "rm:" + c.getRmName()).orElse("rm");
        String note = checked.action().charAt(0) + checked.action().substring(1).toLowerCase() + " · " + checked.section()
                + (checked.reason() != null ? " — " + truncate(checked.reason(), 200) : "");
        int next = baseVersion + 1;
        tx.executeWithoutResult(s -> {
            Brief fresh = find(briefId);
            if (fresh.currentVersion != baseVersion) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The brief changed — ask again");
            }
            versions.save(version(briefId, next, updated, details, actor, note));
            fresh.currentVersion = next;
            audit.record(actor, "BRIEF_EDIT_APPLIED", b.clientId, null,
                    "brief=" + briefId + " v=" + next + " " + checked.action() + " " + checked.section());
        });
        return view(briefId, next);
    }

    private Brief find(long briefId) {
        return briefs.findById(briefId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Brief not found"));
    }

    private BriefVersion version(long briefId, int v, BriefSchema content, Map<String, ChatTools.Citation> details,
                                 String actor, String note) {
        BriefVersion bv = new BriefVersion();
        bv.briefId = briefId;
        bv.version = v;
        bv.content = json(content);
        bv.citationDetails = json(details);
        bv.createdBy = actor;
        bv.changeNote = note;
        bv.createdAt = Instant.now();
        return bv;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T read(String s, Class<T> type) {
        try {
            return MAPPER.readValue(s, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, ChatTools.Citation> citations(String s) {
        try {
            return MAPPER.readValue(s, new TypeReference<LinkedHashMap<String, ChatTools.Citation>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
