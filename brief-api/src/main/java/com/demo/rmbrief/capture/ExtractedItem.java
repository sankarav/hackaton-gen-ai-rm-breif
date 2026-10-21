package com.demo.rmbrief.capture;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "extracted_item")
public class ExtractedItem {

    public enum Category { SUMMARY, PROMISE, LIFE_EVENT, PRODUCT_INTEREST, CONCERN, NEXT_MEETING }

    public enum Decision { PENDING, ACCEPTED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "capture_id", nullable = false)
    public Long captureId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Category category;

    @Column(nullable = false, columnDefinition = "text")
    public String text;

    @Column(nullable = false)
    public String owner;

    @Column(name = "date_phrase")
    public String datePhrase;

    @Column(name = "resolved_date")
    public LocalDate resolvedDate;

    @Column(name = "product_mention")
    public String productMention;

    @Column(name = "resolved_ref")
    public String resolvedRef;

    @Column(name = "resolved_label")
    public String resolvedLabel;

    @Column(name = "source_seqs", nullable = false)
    public String sourceSeqs;

    @Column(nullable = false)
    public String confidence;

    @Column(name = "compliance_flag", nullable = false)
    public boolean complianceFlag;

    /** Problems the RM must look at; any flag leaves the item PENDING. */
    @Column(columnDefinition = "text")
    public String flags;

    /** Informational context (e.g. how a date was interpreted); never blocks approval. */
    @Column(columnDefinition = "text")
    public String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Decision decision = Decision.PENDING;

    @Column(nullable = false)
    public boolean edited;

    public List<Integer> sourceSeqList() {
        if (sourceSeqs == null || sourceSeqs.isBlank()) return List.of();
        return Arrays.stream(sourceSeqs.split(",")).map(String::trim).map(Integer::valueOf).toList();
    }

    public List<String> flagList() {
        return lines(flags);
    }

    public List<String> noteList() {
        return lines(notes);
    }

    public void addFlag(String flag) {
        flags = append(flags, flag);
    }

    public void addNote(String note) {
        notes = append(notes, note);
    }

    private static List<String> lines(String s) {
        if (s == null || s.isBlank()) return List.of();
        return List.of(s.split("\n"));
    }

    private static String append(String existing, String line) {
        List<String> all = new ArrayList<>(lines(existing));
        if (!all.contains(line)) all.add(line);
        return String.join("\n", all);
    }
}
