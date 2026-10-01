package com.tradesentry.core.audit;

import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;

@Entity @Table(name="audit_records",indexes=@Index(name="idx_audit_tx",columnList="transaction_id,occurred_at"))
public class AuditRecord {
 @Id @GeneratedValue private UUID id;
 @Column(name="event_id",nullable=false,unique=true) private UUID eventId;
 @Column(name="transaction_id",nullable=false) private UUID transactionId;
 @Column(name="account_id",nullable=false) private String accountId;
 @Column(name="event_type",nullable=false) private String eventType;
 @Column(nullable=false,columnDefinition="text") private String payload;
 @Column(name="occurred_at",nullable=false) private Instant occurredAt;
 protected AuditRecord(){}
 public AuditRecord(UUID eventId,UUID transactionId,String accountId,String eventType,String payload,Instant occurredAt){
  this.eventId=eventId;this.transactionId=transactionId;this.accountId=accountId;this.eventType=eventType;this.payload=payload;this.occurredAt=occurredAt;
 }
}
