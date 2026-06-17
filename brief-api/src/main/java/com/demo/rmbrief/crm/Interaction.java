package com.demo.rmbrief.crm;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "interaction")
public class Interaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(name = "meeting_date", nullable = false)
    private LocalDate meetingDate;

    @Column(columnDefinition = "text")
    private String notes;

    /** Stored as a JSON array string, e.g. ["Follow up on X","Send Y"]. */
    @Column(columnDefinition = "text")
    private String promises;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Client getClient() { return client; }
    public void setClient(Client client) { this.client = client; }

    public LocalDate getMeetingDate() { return meetingDate; }
    public void setMeetingDate(LocalDate meetingDate) { this.meetingDate = meetingDate; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getPromises() { return promises; }
    public void setPromises(String promises) { this.promises = promises; }
}
