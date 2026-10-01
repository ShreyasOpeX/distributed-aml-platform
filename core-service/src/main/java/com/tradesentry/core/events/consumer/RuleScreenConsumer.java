package com.tradesentry.core.events.consumer;

import com.tradesentry.core.events.config.KafkaTopics;
import com.tradesentry.core.events.model.TransactionEvent;
import com.tradesentry.core.outbox.OutboxService;
import com.tradesentry.core.idempotency.IdempotencyService;
import com.tradesentry.core.transaction.TransactionRepository;
import com.tradesentry.core.transaction.TransactionStatus;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal; import java.util.*;

@Component
public class RuleScreenConsumer {
    private static final Logger log=LoggerFactory.getLogger(RuleScreenConsumer.class);
    private static final String RULE_VERSION="rules-v1";
    private static final BigDecimal REPORTING_THRESHOLD=new BigDecimal("10000");
    private static final BigDecimal STRUCTURING_FLOOR=new BigDecimal("9000");
    private static final BigDecimal LARGE_AMOUNT=new BigDecimal("50000");
    private static final Set<String> HIGH_RISK_COUNTRIES=Set.of("KP","IR","SY");
    private final OutboxService outbox; private final IdempotencyService idempotency;
    private final TransactionRepository repository;

    public RuleScreenConsumer(OutboxService outbox,IdempotencyService idempotency,TransactionRepository repository){
        this.outbox=outbox;this.idempotency=idempotency;this.repository=repository;
    }

    @KafkaListener(topics=KafkaTopics.INGESTED,groupId="rule-screen",
            concurrency="\${core.kafka.screening-concurrency:3}")
    @Transactional
    public void onIngested(TransactionEvent event){
        if(!idempotency.claim(event.eventId(),"rule-screen")) return;
        List<String> reasons=screen(event);
        if(reasons.isEmpty()){
            updateStatus(event,TransactionStatus.CLEARED_EARLY);
            return;
        }
        String reason=String.join("; ",reasons);
        updateStatus(event,TransactionStatus.FLAGGED);
        outbox.enqueue(KafkaTopics.FLAGGED,event.flagged(reason));
        log.info("Flagged [tx={},account={},ruleVersion={}]: {}",event.transactionId(),event.accountId(),RULE_VERSION,reason);
    }

    private List<String> screen(TransactionEvent event){
        List<String> reasons=new ArrayList<>(); BigDecimal amount=event.amount();
        if(amount.compareTo(STRUCTURING_FLOOR)>=0&&amount.compareTo(REPORTING_THRESHOLD)<0)
            reasons.add("possible structuring (amount just under reporting threshold)");
        if(amount.compareTo(LARGE_AMOUNT)>=0) reasons.add("large amount over 50000");
        if(event.counterpartyCountry()!=null&&HIGH_RISK_COUNTRIES.contains(event.counterpartyCountry()))
            reasons.add("high-risk counterparty country: "+event.counterpartyCountry());
        return reasons;
    }

    private void updateStatus(TransactionEvent event,TransactionStatus status){
        repository.findById(event.transactionId()).ifPresent(tx->{tx.setStatus(status);repository.save(tx);});
    }
}
