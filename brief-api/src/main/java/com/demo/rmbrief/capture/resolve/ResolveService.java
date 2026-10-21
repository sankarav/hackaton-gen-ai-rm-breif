package com.demo.rmbrief.capture.resolve;

import com.demo.rmbrief.capture.Capture;
import com.demo.rmbrief.capture.CaptureRepository;
import com.demo.rmbrief.capture.ExtractedItem;
import com.demo.rmbrief.capture.ExtractedItemRepository;
import com.demo.rmbrief.capture.TranscriptSegment;
import com.demo.rmbrief.capture.TranscriptSegmentRepository;
import com.demo.rmbrief.crm.SyntheticProductRepository;
import com.demo.rmbrief.tools.PlaidToolService;
import com.demo.rmbrief.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Deterministic post-processing of extracted items: resolves date phrases and product mentions,
 * and attaches reviewer flags. Items with no flags are pre-accepted; flagged items need an explicit
 * decision before the capture can be approved.
 */
@Service
public class ResolveService {

    private static final Logger log = LoggerFactory.getLogger(ResolveService.class);

    private final CaptureRepository captures;
    private final ExtractedItemRepository items;
    private final TranscriptSegmentRepository segments;
    private final SyntheticProductRepository products;
    private final PlaidToolService plaid;

    public ResolveService(CaptureRepository captures, ExtractedItemRepository items,
                          TranscriptSegmentRepository segments, SyntheticProductRepository products,
                          PlaidToolService plaid) {
        this.captures = captures;
        this.items = items;
        this.segments = segments;
        this.products = products;
        this.plaid = plaid;
    }

    public void resolve(long captureId) {
        Capture capture = captures.findById(captureId).orElseThrow();
        Map<Integer, TranscriptSegment> bySeq = segments.findByCaptureIdOrderBySeq(captureId).stream()
                .collect(Collectors.toMap(s -> s.seq, Function.identity()));
        Holdings holdings = holdings(capture.clientId);

        List<ExtractedItem> all = items.findByCaptureIdOrderById(captureId);
        for (ExtractedItem item : all) {
            item.flags = null;
            item.notes = null;
            item.resolvedDate = null;
            item.resolvedRef = null;
            item.resolvedLabel = null;

            List<TranscriptSegment> sources = item.sourceSeqList().stream().map(bySeq::get).filter(Objects::nonNull).toList();
            if (sources.isEmpty()) item.addFlag("Couldn't trace this to a source line — check before saving");
            if ("low".equals(item.confidence)) item.addFlag("Low confidence — compare with the source");
            if (item.complianceFlag) item.addFlag("Compliance: may need escalation or a complaint record");
            if (sources.stream().anyMatch(s -> s.lowConfidence)) item.addFlag("Source handwriting was hard to read");

            boolean attributable = item.category == ExtractedItem.Category.PROMISE || item.category == ExtractedItem.Category.CONCERN;
            if (attributable && sources.stream().anyMatch(s -> s.speakerInferred)) {
                item.addFlag("Speaker was inferred from the recording — confirm who said this");
            }
            if (item.category == ExtractedItem.Category.PROMISE && "UNKNOWN".equals(item.owner)) {
                item.addFlag("Who made this commitment — RM or client?");
            }

            if (item.datePhrase != null) {
                DateResolver.Result d = DateResolver.resolve(item.datePhrase, capture.meetingDate);
                item.resolvedDate = d.date();
                // A promise's due date drives overdue tracking, so an uncertain one must be confirmed.
                boolean blocks = d.invalid() || (d.approximate() && item.category == ExtractedItem.Category.PROMISE);
                if (d.note() != null) {
                    if (blocks) item.addFlag(d.note()); else item.addNote(d.note());
                }
            }

            if (item.productMention != null) {
                if (holdings.warning() != null) item.addNote(holdings.warning());
                ProductResolver.Result r = ProductResolver.resolve(item.productMention, holdings.list(),
                        item.category == ExtractedItem.Category.PRODUCT_INTEREST);
                item.resolvedRef = r.ref();
                item.resolvedLabel = r.label();
                if (r.note() != null) {
                    if (r.problem()) item.addFlag(r.note()); else item.addNote(r.note());
                }
            }

            if (item.category == ExtractedItem.Category.SUMMARY) {
                // The summary is always kept (the RM edits it rather than dropping it), so its issues are advisory.
                item.flagList().forEach(item::addNote);
                item.flags = null;
            }
            item.decision = item.flagList().isEmpty() ? ExtractedItem.Decision.ACCEPTED : ExtractedItem.Decision.PENDING;
        }
        items.saveAll(all);
        long flagged = all.stream().filter(i -> i.decision == ExtractedItem.Decision.PENDING).count();
        log.info("[capture] {} resolved {} items, {} need review", captureId, all.size(), flagged);
    }

    private record Holdings(List<ProductResolver.Holding> list, String warning) {}

    private Holdings holdings(String clientId) {
        List<ProductResolver.Holding> list = new ArrayList<>();
        NumberFormat usd = NumberFormat.getCurrencyInstance(Locale.US);
        products.findByClientClientId(clientId).forEach(p -> list.add(new ProductResolver.Holding(
                p.getProductType(),
                p.getProductType().replace('_', ' ') + " " + usd.format(p.getBalance())
                        + (p.getMaturityDate() != null ? " · matures " + p.getMaturityDate() : ""),
                p.getProductType(),
                true)));

        ToolResult accounts = plaid.getAccounts(clientId);
        if (!accounts.success()) {
            return new Holdings(list, "Account list unavailable — product match uses CRM records only");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ((Map<String, Object>) accounts.data()).get("accounts");
        for (Map<String, Object> a : rows) {
            String name = String.valueOf(a.get("name"));
            String description = name + " " + a.get("subtype") + " " + a.get("type");
            list.add(new ProductResolver.Holding(String.valueOf(a.get("accountId")), name, description, false));
        }
        return new Holdings(list, null);
    }
}
