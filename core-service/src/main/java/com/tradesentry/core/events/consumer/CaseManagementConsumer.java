package com.tradesentry.core.events.consumer;

import com.tradesentry.core.casefile.*;
import com.tradesentry.core.events.config.KafkaTopics;
import com.tradesentry.core.events.model.TransactionEvent;
import com.tradesentry.core.idempotency.IdempotencyService;
import com.tradesentry.core.transaction.TransactionRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CaseManagementConsumer {
    private final TransactionRepository transactions;
    private final InvestigationCaseRepository cases;
    private final SarCaseRepository sarCases;
    private final IdempotencyService idempotency;

    public CaseManagementConsumer(TransactionRepository transactions,InvestigationCaseRepository cases,
                                  SarCaseRepository sarCases,IdempotencyService idempotency){
        this.transactions=transactions;this.cases=cases;this.sarCases=sarCases;this.idempotency=idempotency;
    }

    @KafkaListener(topics=KafkaTopics.ADJUDICATED,groupId="case-mgmt")
    @Transactional
    public void onAdjudicated(TransactionEvent event){
        if(!idempotency.claim(event.eventId(),"case-mgmt")) return;
        transactions.findById(event.transactionId()).ifPresent(tx ->
            tx.recordAdjudication(event.decision(),event.riskScore()==null?0.0:event.riskScore(),event.reason()));

        cases.save(new InvestigationCase(event.transactionId(),event.accountId(),CaseType.INVESTIGATION,
                event.decision(),event.riskScore()==null?0.0:event.riskScore(),event.reason(),
                event.reason(),event.ruleVersion(),event.scoringVersion()==null?"unknown":event.scoringVersion(),
                event.decisionPolicyVersion()==null?"unknown":event.decisionPolicyVersion(),
                event.investigationDepth()==null?0:event.investigationDepth()));

        if("ESCALATE".equalsIgnoreCase(event.decision())){
            sarCases.save(new SarCase(event.transactionId(),event.accountId(),event.reason()));
        }
    }
}
