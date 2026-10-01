package com.tradesentry.agent.investigation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="investigation_states",uniqueConstraints=@UniqueConstraint(name="uk_investigation_tx",columnNames="transaction_id"))
public class InvestigationCaseState {
    @Id @GeneratedValue private UUID id;
    @Column(name="transaction_id",nullable=false,unique=true) private UUID transactionId;
    @Column(name="account_id",nullable=false) private String accountId;
    @Column(nullable=false) private String status;
    @Column(nullable=false) private int depth;
    @Column(name="risk_score",nullable=false) private double riskScore;
    @Column(nullable=false,columnDefinition="text") private String evidence;
    @Column(nullable=false) private String decision;
    @Column(nullable=false,columnDefinition="text") private String rationale;
    @Column(name="scoring_version",nullable=false) private String scoringVersion;
    @Column(name="policy_version",nullable=false) private String policyVersion;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    protected InvestigationCaseState(){}
    public static InvestigationCaseState from(com.tradesentry.agent.graph.InvestigationState s){
        InvestigationCaseState x=new InvestigationCaseState();
        x.transactionId=s.transactionId(); x.accountId=s.accountId(); x.status="COMPLETED";
        x.depth=s.investigationDepth(); x.riskScore=s.riskScore();
        x.evidence=String.join("\n",s.evidence()); x.decision=s.decision(); x.rationale=s.rationale();
        x.scoringVersion="score-v1"; x.policyVersion="policy-v1"; x.updatedAt=Instant.now();
        return x;
    }
}
