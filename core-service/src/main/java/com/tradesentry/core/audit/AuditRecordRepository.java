package com.tradesentry.core.audit;
import org.springframework.data.jpa.repository.JpaRepository; import java.util.UUID;
public interface AuditRecordRepository extends JpaRepository<AuditRecord,UUID>{}
