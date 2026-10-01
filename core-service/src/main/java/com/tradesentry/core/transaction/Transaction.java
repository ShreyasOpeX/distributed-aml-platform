package com.tradesentry.core.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "counterparty_country")
    private String counterpartyCountry;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column
    private String decision;

    @Column(name = "risk_score")
    private Double riskScore;

    @Column(columnDefinition = "text")
    private String rationale;

    protected Transaction() {
        // for JPA
    }

    public Transaction(UUID id,
                       String accountId,
                       BigDecimal amount,
                       String currency,
                       String counterpartyCountry,
                       TransactionStatus status,
                       Instant createdAt,
                       Instant updatedAt) {
        this.id = id;
        this.accountId = accountId;
        this.amount = amount;
        this.currency = currency;
        this.counterpartyCountry = counterpartyCountry;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCounterpartyCountry() {
        return counterpartyCountry;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getDecision() { return decision; }
    public Double getRiskScore() { return riskScore; }
    public String getRationale() { return rationale; }

    public void recordAdjudication(String decision,double riskScore,String rationale) {
        this.decision=decision;
        this.riskScore=riskScore;
        this.rationale=rationale;
        setStatus(TransactionStatus.ADJUDICATED);
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }
}
