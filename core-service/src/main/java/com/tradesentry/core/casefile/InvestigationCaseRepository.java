package com.tradesentry.core.casefile;
import org.springframework.data.jpa.repository.JpaRepository; import java.util.UUID;
public interface InvestigationCaseRepository extends JpaRepository<InvestigationCase,UUID>{}
