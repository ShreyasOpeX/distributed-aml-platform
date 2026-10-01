package com.tradesentry.core.casefile;

import jakarta.persistence.*;
import java.time.Instant; import java.util.UUID;

@Entity @Table(name="investigation_cases",indexes=@Index(name="idx_case_tx",columnList="transaction_id",unique=true))
public class InvestigationCase {
 @Id @GeneratedValue private UUID id;
 @Column(name="transaction_id",nullable=false,unique=true) private UUID transactionId;
 @Column(name="account_id",nullable=false) private String accountId;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private CaseType type;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private CaseStatus status;
 @Column(nullable=false) private String decision;
 @Column(name="risk_score",nullable=false) private double riskScore;
 @Column(nullable=false,columnDefinition="text") private String rationale;
 @Column(nullable=false,columnDefinition="text") private String evidence;
 @Column(name="rule_version",nullable=false) private String ruleVersion;
 @Column(name="scoring_version",nullable=false) private String scoringVersion;
 @Column(name="decision_policy_version",nullable=false) private String decisionPolicyVersion;
 @Column(name="investigation_depth",nullable=false) private int investigationDepth;
 @Column(name="created_at",nullable=false) private Instant createdAt;
 @Column(name="updated_at",nullable=false) private Instant updatedAt;
 protected InvestigationCase(){}
 public InvestigationCase(UUID tx,String accountId,CaseType type,String decision,double riskScore,String rationale,
 String evidence,String ruleVersion,String scoringVersion,String policyVersion,int depth){
  this.transactionId=tx;this.accountId=accountId;this.type=type;this.status=CaseStatus.OPEN;this.decision=decision;
  this.riskScore=riskScore;this.rationale=rationale;this.evidence=evidence;this.ruleVersion=ruleVersion;
  this.scoringVersion=scoringVersion;this.decisionPolicyVersion=policyVersion;this.investigationDepth=depth;
  this.createdAt=Instant.now();this.updatedAt=this.createdAt;
 }
}
