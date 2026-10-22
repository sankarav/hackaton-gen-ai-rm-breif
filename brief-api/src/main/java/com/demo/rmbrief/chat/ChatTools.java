package com.demo.rmbrief.chat;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUnion;
import com.demo.rmbrief.capture.ClientPromise;
import com.demo.rmbrief.capture.ClientPromiseRepository;
import com.demo.rmbrief.delta.DeltaService;
import com.demo.rmbrief.llm.BriefSchema;
import com.demo.rmbrief.tools.CrmContextService;
import com.demo.rmbrief.tools.PlaidToolService;
import com.demo.rmbrief.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Tools the chat model can call. All are read-only and bound to the brief's client — the model
 * never passes a client ID. Every total, count and difference is computed here in Java, so the
 * model only explains numbers, never derives them. Every ID a tool returns becomes citable.
 */
@Component
public class ChatTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ROWS = 100;
    private static final int MAX_GROUPS = 25;

    public record Citation(String label, String detail) {}

    public record Result(String content, boolean error) {}

    /** State for one chat turn: data fetched at most once, every citable ID seen, edit proposals made. */
    public static final class Turn {
        final String clientId;
        final BriefSchema brief;
        final int version;
        final Set<String> knownIds;
        final Map<String, Citation> seen = new LinkedHashMap<>();
        final List<Map<String, Object>> proposals = new ArrayList<>();
        private List<Txn> txns;
        private List<Map<String, Object>> accounts;

        public Turn(String clientId, BriefSchema brief, int version, Set<String> knownIds) {
            this.clientId = clientId;
            this.brief = brief;
            this.version = version;
            this.knownIds = knownIds;
        }

        boolean isKnown(String id) {
            return knownIds.contains(id) || seen.containsKey(id);
        }
    }

    record Txn(String id, String accountId, LocalDate date, String merchant, String category,
               BigDecimal amount, String direction) {}

    private final PlaidToolService plaid;
    private final CrmContextService crm;
    private final ClientPromiseRepository promises;

    public ChatTools(PlaidToolService plaid, CrmContextService crm, ClientPromiseRepository promises) {
        this.plaid = plaid;
        this.crm = crm;
        this.promises = promises;
    }

    // ── definitions ───────────────────────────────────────────────────────────

    public List<ToolUnion> definitions() {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("since", prop("string", "Start date inclusive, YYYY-MM-DD"));
        range.put("until", prop("string", "End date inclusive, YYYY-MM-DD"));
        range.put("direction", enumProp("outflow = money out, inflow = money in", "outflow", "inflow", "all"));
        range.put("account_id", prop("string", "Limit to one accountId"));
        range.put("merchant_contains", prop("string", "Case-insensitive match on merchant/description"));

        Map<String, Object> search = new LinkedHashMap<>(range);
        search.put("min_amount", prop("number", "Only transactions at least this large (absolute dollars)"));
        search.put("limit", prop("integer", "Max rows to return, 1-100 (default 25). Totals always cover every match."));

        Map<String, Object> summarize = new LinkedHashMap<>(range);
        summarize.put("group_by", enumProp("How to group", "merchant", "category", "month", "account"));

        Map<String, Object> compare = new LinkedHashMap<>();
        compare.put("a_since", prop("string", "Period A start, YYYY-MM-DD"));
        compare.put("a_until", prop("string", "Period A end, YYYY-MM-DD"));
        compare.put("b_since", prop("string", "Period B start, YYYY-MM-DD"));
        compare.put("b_until", prop("string", "Period B end, YYYY-MM-DD"));
        compare.put("direction", enumProp("outflow = money out, inflow = money in", "outflow", "inflow"));
        compare.put("account_id", prop("string", "Limit to one accountId"));
        compare.put("merchant_contains", prop("string", "Case-insensitive match on merchant/description"));

        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("section", enumProp("Brief section to change", "changedSinceLastMeeting", "watchOuts", "opportunities", "talkingPoints"));
        edit.put("action", enumProp("ADD a new item, REPLACE an existing one, or REMOVE one", "ADD", "REPLACE", "REMOVE"));
        edit.put("index", prop("integer", "0-based index of the item to REPLACE or REMOVE, as listed in the brief"));
        edit.put("text", prop("string", "New item text for ADD or REPLACE"));
        edit.put("severity", enumProp("Watch-out severity (required when adding a watch-out)", "high", "med", "low"));
        edit.put("citations", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "IDs supporting the item (accountId, transactionId, productType or promiseId). Required except for talkingPoints and REMOVE."));
        edit.put("reason", prop("string", "One sentence the RM sees explaining why"));

        return List.of(
                tool("get_accounts", "List the client's accounts with current and available balances.", Map.of(), List.of()),
                tool("search_transactions", "Find the client's transactions with filters. Returns matching rows (newest first) plus totals computed over every match.", search, List.of()),
                tool("summarize_transactions", "Group the client's transactions and total them (by merchant, category, month or account). Use this instead of adding up numbers yourself.", summarize, List.of("group_by")),
                tool("compare_periods", "Compare transaction totals between two date ranges; returns both totals, the difference and the percent change.", compare, List.of("a_since", "a_until", "b_since", "b_until")),
                tool("get_holdings", "Investment holdings and securities.", Map.of(), List.of()),
                tool("get_liabilities", "Credit cards, mortgages and student loans.", Map.of(), List.of()),
                tool("get_relationship_notes", "CRM context: last meeting notes, segment, tenure, products such as CDs, and tracked promises with due status.", Map.of(), List.of()),
                tool("propose_brief_edit", "Propose one change to the brief. The RM sees it and decides whether to apply it; it is never applied automatically. Call once per change.", edit, List.of("section", "action", "reason")));
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static Map<String, Object> enumProp(String description, String... values) {
        return Map.of("type", "string", "enum", List.of(values), "description", description);
    }

    private static ToolUnion tool(String name, String description, Map<String, Object> props, List<String> required) {
        Tool.InputSchema.Properties.Builder pb = Tool.InputSchema.Properties.builder();
        props.forEach((k, v) -> pb.putAdditionalProperty(k, JsonValue.from(v)));
        Tool.InputSchema.Builder sb = Tool.InputSchema.builder().type(JsonValue.from("object")).properties(pb.build());
        required.forEach(sb::addRequired);
        return ToolUnion.ofTool(Tool.builder().name(name).description(description).inputSchema(sb.build()).build());
    }

    public static String label(String toolName) {
        return switch (toolName) {
            case "get_accounts" -> "Checking account balances";
            case "search_transactions" -> "Searching transactions";
            case "summarize_transactions" -> "Totalling transactions";
            case "compare_periods" -> "Comparing periods";
            case "get_holdings" -> "Reading investment holdings";
            case "get_liabilities" -> "Reading liabilities";
            case "get_relationship_notes" -> "Reading CRM notes and promises";
            case "propose_brief_edit" -> "Drafting a brief change for your approval";
            default -> toolName;
        };
    }

    // ── execution ─────────────────────────────────────────────────────────────

    public Result execute(String name, Map<String, Object> in, Turn turn) {
        try {
            Object out = switch (name) {
                case "get_accounts" -> accounts(turn);
                case "search_transactions" -> search(in, turn);
                case "summarize_transactions" -> summarize(in, turn);
                case "compare_periods" -> compare(in, turn);
                case "get_holdings" -> passthrough(plaid.getHoldings(turn.clientId), turn);
                case "get_liabilities" -> passthrough(plaid.getLiabilities(turn.clientId), turn);
                case "get_relationship_notes" -> notes(turn);
                case "propose_brief_edit" -> propose(in, turn);
                default -> throw new IllegalArgumentException("Unknown tool " + name);
            };
            return new Result(MAPPER.writeValueAsString(out), false);
        } catch (IllegalArgumentException e) {
            return new Result(e.getMessage(), true);
        } catch (Exception e) {
            return new Result("Tool failed: " + e.getMessage(), true);
        }
    }

    private Map<String, Object> accounts(Turn t) {
        List<Map<String, Object>> rows = loadAccounts(t);
        return Map.of("accounts", rows);
    }

    private Map<String, Object> search(Map<String, Object> in, Turn t) {
        List<Txn> matches = filter(loadTxns(t), in);
        BigDecimal min = in.get("min_amount") instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null;
        if (min != null) matches = matches.stream().filter(x -> x.amount.compareTo(min) >= 0).toList();
        int limit = in.get("limit") instanceof Number n ? Math.max(1, Math.min(MAX_ROWS, n.intValue())) : 25;

        List<Map<String, Object>> rows = matches.stream()
                .sorted(Comparator.comparing(Txn::date).reversed())
                .limit(limit)
                .map(x -> row(x, t))
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matchCount", matches.size());
        out.put("returned", rows.size());
        out.put("totalOutflow", sum(matches, "outflow"));
        out.put("totalInflow", sum(matches, "inflow"));
        out.put("transactions", rows);
        return out;
    }

    private Map<String, Object> summarize(Map<String, Object> in, Turn t) {
        String groupBy = str(in.get("group_by"));
        Function<Txn, String> key = switch (groupBy == null ? "" : groupBy) {
            case "merchant" -> Txn::merchant;
            case "category" -> Txn::category;
            case "month" -> x -> x.date.toString().substring(0, 7);
            case "account" -> Txn::accountId;
            default -> throw new IllegalArgumentException("group_by must be merchant, category, month or account");
        };
        List<Txn> matches = filter(loadTxns(t), in);
        Map<String, List<Txn>> groups = matches.stream().collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> rows = groups.entrySet().stream()
                .map(e -> {
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("group", e.getKey());
                    g.put("count", e.getValue().size());
                    g.put("totalOutflow", sum(e.getValue(), "outflow"));
                    g.put("totalInflow", sum(e.getValue(), "inflow"));
                    g.put("exampleTransactionIds", e.getValue().stream()
                            .sorted(Comparator.comparing(Txn::amount).reversed()).limit(3)
                            .map(x -> { cite(x, t); return x.id; }).toList());
                    if (groupBy.equals("account")) citeAccount(e.getKey(), t);
                    return g;
                })
                .sorted(Comparator.comparing((Map<String, Object> g) -> ((BigDecimal) g.get("totalOutflow")).add((BigDecimal) g.get("totalInflow"))).reversed())
                .limit(MAX_GROUPS)
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groupBy", groupBy);
        out.put("matchCount", matches.size());
        out.put("groupCount", groups.size());
        out.put("totalOutflow", sum(matches, "outflow"));
        out.put("totalInflow", sum(matches, "inflow"));
        out.put("groups", rows);
        return out;
    }

    private Map<String, Object> compare(Map<String, Object> in, Turn t) {
        String direction = Optional.ofNullable(str(in.get("direction"))).orElse("outflow");
        Map<String, Object> common = new HashMap<>(in);
        common.put("direction", direction);
        Map<String, Object> a = new HashMap<>(common);
        a.put("since", in.get("a_since"));
        a.put("until", in.get("a_until"));
        Map<String, Object> b = new HashMap<>(common);
        b.put("since", in.get("b_since"));
        b.put("until", in.get("b_until"));

        List<Txn> all = loadTxns(t);
        List<Txn> ta = filter(all, a);
        List<Txn> tb = filter(all, b);
        BigDecimal sa = sum(ta, direction);
        BigDecimal sb = sum(tb, direction);
        BigDecimal diff = sb.subtract(sa);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("direction", direction);
        out.put("periodA", Map.of("since", in.get("a_since"), "until", in.get("a_until"), "total", sa, "count", ta.size()));
        out.put("periodB", Map.of("since", in.get("b_since"), "until", in.get("b_until"), "total", sb, "count", tb.size()));
        out.put("differenceBminusA", diff);
        out.put("percentChange", sa.signum() == 0 ? null : diff.multiply(BigDecimal.valueOf(100)).divide(sa, 1, RoundingMode.HALF_UP));
        return out;
    }

    private Object passthrough(ToolResult r, Turn t) {
        if (!r.success()) throw new IllegalArgumentException("Data unavailable: " + r.error());
        try {
            // Make any accountId in the payload citable.
            String json = MAPPER.writeValueAsString(r.data());
            for (Map<String, Object> acct : loadAccounts(t)) {
                String id = (String) acct.get("accountId");
                if (json.contains(id)) citeAccount(id, t);
            }
        } catch (Exception ignored) {
            // citations are best-effort for these payloads
        }
        return r.data();
    }

    private Map<String, Object> notes(Turn t) {
        ToolResult r = crm.getCrmContext(t.clientId);
        if (!r.success()) throw new IllegalArgumentException("CRM unavailable: " + r.error());
        LocalDate today = LocalDate.now();
        List<Object> tracked = new ArrayList<>();
        for (ClientPromise p : promises.findByClientIdAndStatusOrderByDueDateAsc(t.clientId, ClientPromise.Status.OPEN)) {
            var open = DeltaService.toOpenPromise(p, today);
            tracked.add(open);
            t.seen.put(open.promiseId(), new Citation("Promise",
                    p.text + "\n" + ("CLIENT".equals(p.owner) ? "Client" : "RM") + " owns it\n"
                            + (p.dueDate != null ? "Due " + p.dueDate + " (" + open.status() + ")" : "No due date")
                            + "\nPromise ID: " + open.promiseId()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("crm", r.data());
        out.put("trackedOpenPromises", tracked);
        out.put("today", today.toString());
        return out;
    }

    private Map<String, Object> propose(Map<String, Object> in, Turn t) {
        @SuppressWarnings("unchecked")
        List<String> raw = in.get("citations") instanceof List<?> l ? (List<String>) l : List.of();
        List<String> kept = raw.stream().filter(t::isKnown).distinct().toList();
        BriefEdit edit = new BriefEdit(str(in.get("section")), str(in.get("action")),
                in.get("index") instanceof Number n ? n.intValue() : null,
                str(in.get("text")), str(in.get("severity")), kept, str(in.get("reason")));

        // Throws IllegalArgumentException (returned to the model as a tool error) if the edit doesn't fit the brief.
        edit.applyTo(t.brief);

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("proposalId", UUID.randomUUID().toString().substring(0, 8));
        p.put("baseVersion", t.version);
        p.put("edit", edit);
        p.put("before", edit.targetText(t.brief));
        p.put("droppedCitations", raw.stream().filter(id -> !kept.contains(id)).toList());
        t.proposals.add(p);

        return Map.of("status", "Shown to the RM for approval. It is NOT applied unless they click Apply.",
                "proposalId", p.get("proposalId"));
    }

    // ── data helpers ──────────────────────────────────────────────────────────

    /** Live citation details for every transaction and account, used to verify edits at apply time. */
    public Map<String, Citation> liveCitations(String clientId) {
        Turn t = new Turn(clientId, null, 0, Set.of());
        loadAccounts(t).forEach(a -> citeAccount((String) a.get("accountId"), t));
        loadTxns(t).forEach(x -> cite(x, t));
        return t.seen;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadAccounts(Turn t) {
        if (t.accounts == null) {
            ToolResult r = plaid.getAccounts(t.clientId);
            if (!r.success()) throw new IllegalArgumentException("Accounts unavailable: " + r.error());
            t.accounts = (List<Map<String, Object>>) ((Map<String, Object>) r.data()).get("accounts");
            t.accounts.forEach(a -> citeAccount((String) a.get("accountId"), t));
        }
        return t.accounts;
    }

    @SuppressWarnings("unchecked")
    private List<Txn> loadTxns(Turn t) {
        if (t.txns == null) {
            ToolResult r = plaid.getTransactions(t.clientId, null);
            if (!r.success()) throw new IllegalArgumentException("Transactions unavailable: " + r.error());
            List<Map<String, Object>> raw = (List<Map<String, Object>>) ((Map<String, Object>) r.data()).get("transactions");
            t.txns = raw.stream().map(m -> {
                double amt = ((Number) m.get("amount")).doubleValue();
                String merchant = str(m.get("merchantName"));
                if (merchant == null || merchant.isBlank()) merchant = str(m.get("name"));
                List<?> cats = m.get("category") instanceof List<?> l ? l : List.of();
                String category = cats.isEmpty() ? "Uncategorized" : cats.stream().map(String::valueOf).collect(Collectors.joining(" > "));
                // Plaid: positive amount = money out of the account.
                return new Txn((String) m.get("transactionId"), (String) m.get("accountId"),
                        LocalDate.parse((String) m.get("date")), merchant, category,
                        BigDecimal.valueOf(Math.abs(amt)).setScale(2, RoundingMode.HALF_UP), amt >= 0 ? "outflow" : "inflow");
            }).toList();
        }
        return t.txns;
    }

    private static List<Txn> filter(List<Txn> txns, Map<String, Object> in) {
        LocalDate since = date(in.get("since"), "since");
        LocalDate until = date(in.get("until"), "until");
        String direction = Optional.ofNullable(str(in.get("direction"))).orElse("all");
        String account = str(in.get("account_id"));
        String merchant = str(in.get("merchant_contains"));
        Predicate<Txn> p = x -> (since == null || !x.date.isBefore(since))
                && (until == null || !x.date.isAfter(until))
                && (direction.equals("all") || direction.equals(x.direction))
                && (account == null || account.equals(x.accountId))
                && (merchant == null || x.merchant.toLowerCase(Locale.ROOT).contains(merchant.toLowerCase(Locale.ROOT)));
        return txns.stream().filter(p).toList();
    }

    private Map<String, Object> row(Txn x, Turn t) {
        cite(x, t);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transactionId", x.id);
        m.put("date", x.date.toString());
        m.put("merchant", x.merchant);
        m.put("amount", x.amount);
        m.put("direction", x.direction);
        m.put("category", x.category);
        m.put("accountId", x.accountId);
        return m;
    }

    private void cite(Txn x, Turn t) {
        t.seen.putIfAbsent(x.id, new Citation(x.merchant,
                "$" + x.amount.toPlainString() + " " + (x.direction.equals("outflow") ? "out" : "in") + " on " + x.date
                        + "\n" + x.category + "\nTransaction ID: " + x.id));
    }

    private void citeAccount(String accountId, Turn t) {
        if (t.accounts == null) return;
        t.accounts.stream().filter(a -> accountId.equals(a.get("accountId"))).findFirst().ifPresent(a ->
                t.seen.putIfAbsent(accountId, new Citation(String.valueOf(a.get("name")),
                        a.get("type") + (a.get("subtype") != null ? " · " + a.get("subtype") : "")
                                + (a.get("currentBalance") != null ? "\nBalance: $" + a.get("currentBalance") : "")
                                + "\nAccount ID: " + accountId)));
    }

    private static BigDecimal sum(List<Txn> txns, String direction) {
        return txns.stream()
                .filter(x -> direction.equals("all") || direction.equals(x.direction))
                .map(Txn::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static LocalDate date(Object v, String field) {
        String s = str(v);
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be YYYY-MM-DD, got " + s);
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
