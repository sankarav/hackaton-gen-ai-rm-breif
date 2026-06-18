package com.demo.rmbrief.llm;

import java.util.List;

/**
 * Strict output schema for the synthesized client brief.
 * Every factual claim in changedSinceLastMeeting, watchOuts, and opportunities
 * must carry citations that resolve to real IDs in the ChangeReport.
 */
public record BriefSchema(
        String clientId,
        Snapshot snapshot,
        List<Section> changedSinceLastMeeting,
        List<WatchOut> watchOuts,
        List<Section> opportunities,
        List<String> talkingPoints,
        LastInteractionRecap lastInteractionRecap
) {
    public record Snapshot(
            String name,
            String segment,
            double tenureYears,
            double totalRelationshipValue
    ) {}

    public record Section(
            String summary,
            List<String> citations
    ) {}

    public record WatchOut(
            String summary,
            String severity,
            List<String> citations
    ) {}

    public record LastInteractionRecap(
            String date,
            String summary,
            List<String> openPromises
    ) {}
}
