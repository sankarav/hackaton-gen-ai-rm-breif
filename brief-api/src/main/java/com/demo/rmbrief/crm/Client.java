package com.demo.rmbrief.crm;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "client")
public class Client {

    @Id
    @Column(name = "client_id")
    private String clientId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String segment;

    @Column(name = "relationship_start")
    private LocalDate relationshipStart;

    @Column(name = "rm_name")
    private String rmName;

    @Column(name = "plaid_access_token")
    private String plaidAccessToken;

    @Column(name = "plaid_item_id")
    private String plaidItemId;

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getSegment() { return segment; }
    public void setSegment(String segment) { this.segment = segment; }

    public LocalDate getRelationshipStart() { return relationshipStart; }
    public void setRelationshipStart(LocalDate relationshipStart) { this.relationshipStart = relationshipStart; }

    public String getRmName() { return rmName; }
    public void setRmName(String rmName) { this.rmName = rmName; }

    public String getPlaidAccessToken() { return plaidAccessToken; }
    public void setPlaidAccessToken(String plaidAccessToken) { this.plaidAccessToken = plaidAccessToken; }

    public String getPlaidItemId() { return plaidItemId; }
    public void setPlaidItemId(String plaidItemId) { this.plaidItemId = plaidItemId; }
}
