package com.demo.rmbrief.capture.resolve;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Matches a verbatim product mention ("the CD", "her savings") to one of the client's holdings.
 * Pure keyword matching — deterministic, explainable, and never invents an account.
 */
public final class ProductResolver {

    /** ref is a Plaid accountId or a CRM productType. crm=true for the bank's own CRM product records. */
    public record Holding(String ref, String label, String description, boolean crm) {}

    /** ref/label are null when unresolved; note is shown to the reviewer; problem=true raises a flag. */
    public record Result(String ref, String label, String note, boolean problem) {}

    private static final Map<String, List<String>> ALIASES = new LinkedHashMap<>();
    static {
        ALIASES.put("cd", List.of("cd", "cds", "certificate of deposit", "certificate", "time deposit"));
        ALIASES.put("term deposit", List.of("term deposit", "term_deposit"));
        ALIASES.put("money market", List.of("money market", "mmda"));
        ALIASES.put("savings", List.of("savings", "saving", "high yield", "hysa"));
        ALIASES.put("checking", List.of("checking", "chequing", "current account"));
        ALIASES.put("credit card", List.of("credit card", "card", "visa", "mastercard", "amex"));
        ALIASES.put("heloc", List.of("heloc", "home equity", "line of credit"));
        ALIASES.put("mortgage", List.of("mortgage", "home loan"));
        ALIASES.put("student loan", List.of("student loan", "student"));
        ALIASES.put("auto loan", List.of("auto loan", "car loan"));
        ALIASES.put("ira", List.of("ira", "roth", "retirement account"));
        ALIASES.put("401k", List.of("401k", "401(k)", "401 k"));
        ALIASES.put("brokerage", List.of("brokerage", "investment account", "investments", "portfolio", "stocks"));
    }

    private ProductResolver() {}

    public static Result resolve(String mention, List<Holding> holdings, boolean interestOnly) {
        Set<String> wanted = keys(mention);
        String m = mention.toLowerCase(Locale.ROOT);

        List<Holding> candidates = holdings.stream()
                .filter(h -> !wanted.isEmpty() ? intersects(keys(h.description()), wanted)
                        : m.contains(h.label().toLowerCase(Locale.ROOT)))
                .toList();

        // The bank's own CRM product record (which carries maturity/rate) wins over an aggregated account.
        if (candidates.size() > 1 && candidates.stream().anyMatch(Holding::crm)) {
            candidates = candidates.stream().filter(Holding::crm).toList();
        }

        if (candidates.size() == 1) {
            Holding h = candidates.get(0);
            return new Result(h.ref(), h.label(), null, false);
        }
        if (candidates.size() > 1) {
            String names = candidates.stream().map(Holding::label).collect(Collectors.joining(", "));
            return new Result(null, null, "“" + mention + "” matches " + candidates.size() + " accounts (" + names + ") — pick one", true);
        }
        if (wanted.isEmpty()) {
            return new Result(null, null, "“" + mention + "” isn't a product we recognise — check it", !interestOnly);
        }
        String product = wanted.stream().map(k -> k.length() <= 5 ? k.toUpperCase(Locale.ROOT) : k)
                .collect(Collectors.joining("/"));
        if (interestOnly) {
            return new Result(null, "Not currently held: " + product, null, false);
        }
        return new Result(null, null, "Client has no " + product + " on record — check “" + mention + "”", true);
    }

    static Set<String> keys(String text) {
        String t = text.toLowerCase(Locale.ROOT).replace('_', ' ');
        Set<String> found = new LinkedHashSet<>();
        ALIASES.forEach((key, aliases) -> {
            for (String a : aliases) {
                if (Pattern.compile("(?<![a-z0-9])" + Pattern.quote(a) + "(?![a-z0-9])").matcher(t).find()) {
                    found.add(key);
                    break;
                }
            }
        });
        return found;
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        for (String x : a) if (b.contains(x)) return true;
        return false;
    }
}
