package com.tradesentry.agent.events;

import com.tradesentry.agent.graph.InvestigationState;
import com.tradesentry.agent.graph.StateGraph;
import com.tradesentry.agent.idempotency.ProcessedEvent;
import com.tradesentry.agent.idempotency.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Component
public class FlaggedTransactionConsumer {
    private static final Logger log=LoggerFactory.getLogger(FlaggedTransactionConsumer.class);
    private static final String FLAGGED="transactions.flagged";
    private static final String ADJUDICATED="transactions.adjudicated";
    private final StateGraph<InvestigationState> graph;
    private final KafkaTemplate<String,TransactionEvent> kafkaTemplate;
    private final ProcessedEventRepository processedEvents;

    public FlaggedTransactionConsumer(StateGraph<InvestigationState> graph,
                                      KafkaTemplate<String,TransactionEvent> kafkaTemplate,
                                      ProcessedEventRepository processedEvents){
        this.graph=graph; this.kafkaTemplate=kafkaTemplate; this.processedEvents=processedEvents;
    }

    @KafkaListener(topics=FLAGGED,groupId="investigation-agent",concurrency="${agent.kafka.concurrency:3}")
    @Transactional
    public void onFlagged(TransactionEvent event){
        if(!processedEvents.claim(event.eventId(),"investigation-agent")) {
            log.info("Duplicate flagged event ignored [event={},tx={}]",event.eventId(),event.transactionId());
            return;
        }
        InvestigationState initial=InvestigationState.start(event.transactionId(),event.accountId(),
                event.amount(),event.counterpartyCountry(),event.reason());
        InvestigationState result=graph.invoke(initial);
        kafkaTemplate.send(ADJUDICATED,event.accountId(),
                event.adjudicated(result.decision(),result.rationale(),result.riskScore(),
                        result.investigationDepth(),"score-v1","policy-v1"));
    }
}
