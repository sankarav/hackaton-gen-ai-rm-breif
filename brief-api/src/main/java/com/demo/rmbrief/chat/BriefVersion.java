package com.demo.rmbrief.chat;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "brief_version")
public class BriefVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "brief_id", nullable = false)
    public Long briefId;

    @Column(nullable = false)
    public int version;

    @Column(nullable = false, columnDefinition = "text")
    public String content;

    @Column(name = "citation_details", nullable = false, columnDefinition = "text")
    public String citationDetails;

    @Column(name = "created_by", nullable = false)
    public String createdBy;

    @Column(name = "change_note")
    public String changeNote;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
