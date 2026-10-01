package com.tradesentry.agent.investigation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface InvestigationCaseStateRepository extends JpaRepository<InvestigationCaseState,UUID>{}
