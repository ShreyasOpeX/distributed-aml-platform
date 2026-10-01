package com.tradesentry.core.casefile;
import org.springframework.data.jpa.repository.JpaRepository; import java.util.UUID;
public interface SarCaseRepository extends JpaRepository<SarCase,UUID>{}
