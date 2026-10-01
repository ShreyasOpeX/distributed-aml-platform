package com.tradesentry.core.casefile;

import jakarta.persistence.*;
import java.time.Instant; import java.util.UUID;

@Entity @Table(name="sar_cases",indexes=@Index(name="idx_sar_tx",columnList="transaction_id",unique=true))
public class SarCase {
 @Id @GeneratedValue private UUID id;
 @Column(name="transaction_id",nullable=false,unique=true) private UUID transactionId;
 @Column(name="account_id",nullable=false) private String accountId;
 @Column(nullable=false) private String status;
 @Column(nullable=false,columnDefinition="text") private String rationale;
 @Column(name="created_at",nullable=false) private Instant createdAt;
 protected SarCase(){}
 public SarCase(UUID tx,String accountId,String rationale){
  this.transactionId=tx;this.accountId=accountId;this.status="OPEN";this.rationale=rationale;this.createdAt=Instant.now();
 }
}
