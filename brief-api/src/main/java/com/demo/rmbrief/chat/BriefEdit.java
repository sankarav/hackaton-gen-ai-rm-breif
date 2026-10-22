package com.demo.rmbrief.chat;

import com.demo.rmbrief.llm.BriefSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One proposed change to a brief section. Claude can only propose these; they are applied
 * (as a new brief version) when the RM clicks Apply.
 *
 * @param index 0-based item index; required for REPLACE and REMOVE
 */
public record BriefEdit(String section, String action, Integer index, String text, String severity,
                        List<String> citations, String reason) {

    public static final Set<String> SECTIONS = Set.of("changedSinceLastMeeting", "watchOuts", "opportunities", "talkingPoints");
    public static final Set<String> ACTIONS = Set.of("ADD", "REPLACE", "REMOVE");
    private static final Set<String> SEVERITIES = Set.of("high", "med", "low");

    public boolean needsCitations() {
        return !"talkingPoints".equals(section) && !"REMOVE".equals(action);
    }

    public BriefEdit withCitations(List<String> kept) {
        return new BriefEdit(section, action, index, text, severity, kept, reason);
    }

    /** Current text of the item this edit targets, or null for ADD. */
    public String targetText(BriefSchema b) {
        if ("ADD".equals(action)) return null;
        return switch (section) {
            case "changedSinceLastMeeting" -> b.changedSinceLastMeeting().get(index).summary();
            case "watchOuts" -> b.watchOuts().get(index).summary();
            case "opportunities" -> b.opportunities().get(index).summary();
            default -> b.talkingPoints().get(index);
        };
    }

    /** Validates against the brief and returns the edited copy. Throws IllegalArgumentException with a reason. */
    public BriefSchema applyTo(BriefSchema b) {
        if (!SECTIONS.contains(section)) throw new IllegalArgumentException("Unknown section: " + section);
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("action must be ADD, REPLACE or REMOVE");
        int size = switch (section) {
            case "changedSinceLastMeeting" -> b.changedSinceLastMeeting().size();
            case "watchOuts" -> b.watchOuts().size();
            case "opportunities" -> b.opportunities().size();
            default -> b.talkingPoints().size();
        };
        if (!"ADD".equals(action) && (index == null || index < 0 || index >= size)) {
            throw new IllegalArgumentException("index must be between 0 and " + (size - 1) + " for " + section);
        }
        if (!"REMOVE".equals(action) && (text == null || text.isBlank())) {
            throw new IllegalArgumentException("text is required for " + action);
        }
        if (needsCitations() && (citations == null || citations.isEmpty())) {
            throw new IllegalArgumentException(section + " items need at least one valid citation ID");
        }
        if ("watchOuts".equals(section) && "ADD".equals(action) && (severity == null || !SEVERITIES.contains(severity))) {
            throw new IllegalArgumentException("severity must be high, med or low for a new watch-out");
        }
        if (severity != null && !SEVERITIES.contains(severity)) {
            throw new IllegalArgumentException("severity must be high, med or low");
        }

        String t = text == null ? null : text.strip();
        List<String> cites = citations == null ? List.of() : List.copyOf(citations);
        return switch (section) {
            case "changedSinceLastMeeting" -> new BriefSchema(b.clientId(), b.snapshot(),
                    edit(b.changedSinceLastMeeting(), new BriefSchema.Section(t, cites)), b.watchOuts(),
                    b.opportunities(), b.talkingPoints(), b.lastInteractionRecap());
            case "opportunities" -> new BriefSchema(b.clientId(), b.snapshot(), b.changedSinceLastMeeting(),
                    b.watchOuts(), edit(b.opportunities(), new BriefSchema.Section(t, cites)), b.talkingPoints(),
                    b.lastInteractionRecap());
            case "watchOuts" -> {
                String sev = severity != null ? severity
                        : "REPLACE".equals(action) ? b.watchOuts().get(index).severity() : null;
                yield new BriefSchema(b.clientId(), b.snapshot(), b.changedSinceLastMeeting(),
                        edit(b.watchOuts(), new BriefSchema.WatchOut(t, sev, cites)), b.opportunities(),
                        b.talkingPoints(), b.lastInteractionRecap());
            }
            default -> new BriefSchema(b.clientId(), b.snapshot(), b.changedSinceLastMeeting(), b.watchOuts(),
                    b.opportunities(), edit(b.talkingPoints(), t), b.lastInteractionRecap());
        };
    }

    private <T> List<T> edit(List<T> items, T item) {
        List<T> out = new ArrayList<>(items);
        switch (action) {
            case "ADD" -> out.add(item);
            case "REPLACE" -> out.set(index, item);
            default -> out.remove((int) index);
        }
        return out;
    }
}
