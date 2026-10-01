package com.tradesentry.agent.events;

import com.tradesentry.agent.graph.InvestigationState;
import com.tradesentry.agent.graph.StateGraph;
import com.tradesentry.agent.idempotency.ProcessedEventRepository;
import com.tradesentry.agent.investigation.InvestigationCaseState;
import com.tradesentry.agent.investigation.InvestigationCaseStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class FlaggedTransactionConsumer {
    private static final Logger log=LoggerFactory.getLogger(FlaggedTransactionConsumer.class);
    private static final String FLAGGED="transactions.flagged";
    private static final String ADJUDICATED="transactions.adjudicated";
    private final StateGraph<InvestigationState> graph;
    private final KafkaTemplate<String,TransactionEvent> kafkaTemplate;
    private final ProcessedEventRepository processedEvents;
    private final InvestigationCaseStateRepository stateRepository;

    public FlaggedTransactionConsumer(StateGraph<InvestigationState> graph,
                                      KafkaTemplate<String,TransactionEvent> kafkaTemplate,
                                      ProcessedEventRepository processedEvents,
                                      InvestigationCaseStateRepository stateRepository){
        this.graph=graph; this.kafkaTemplate=kafkaTemplate; this.processedEvents=processedEvents;
        this.stateRepository=stateRepository;
    }

    @KafkaListener(topics=FLAGGED,groupId="investigation-agent",concurrency="\${agent.kafka.concurrency:3}")
    @Transactional
    public void onFlagged(TransactionEvent event){
        if(!processedEvents.claim(event.eventId(),"investigation-agent")) return;
        InvestigationState initial=InvestigationState.start(event.transactionId(),event.accountId(),
                event.amount(),event.counterpartyCountry(),event.reason());
        InvestigationState result=graph.invoke(initial);
        stateRepository.save(InvestigationCaseState.from(result));
        kafkaTemplate.send(ADJUDICATED,event.accountId(),
                event.adjudicated(result.decision(),result.rationale(),result.riskScore(),
                        result.investigationDepth(),"score-v1","policy-v1"));
        log.info("Adjudicated [event={},tx={},decision={}]",event.eventId(),event.transactionId(),result.decision());
    }
}
