package com.tradesentry.core.events.consumer;

import com.tradesentry.core.events.config.KafkaTopics;
import com.tradesentry.core.events.model.TransactionEvent;
import com.tradesentry.core.events.producer.TransactionEventProducer;
import com.tradesentry.core.transaction.TransactionRepository;
import com.tradesentry.core.transaction.TransactionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Applies deterministic AML screening rules to every ingested transaction.
 *
 * <p>This consumer runs in its own group "rule-screen", separate from "audit". Kafka delivers
 * every ingested event to each group independently, so audit and rule-screening both receive
 * the full stream without competing for records — this is the fan-out pattern.
 */
@Component
public class RuleScreenConsumer {

    private static final Logger log = LoggerFactory.getLogger(RuleScreenConsumer.class);

    private static final BigDecimal REPORTING_THRESHOLD = new BigDecimal("10000");
    private static final BigDecimal STRUCTURING_FLOOR = new BigDecimal("9000");
    private static final BigDecimal LARGE_AMOUNT = new BigDecimal("50000");
    private static final Set<String> HIGH_RISK_COUNTRIES = Set.of("KP", "IR", "SY");

    private final TransactionEventProducer producer;
    private final TransactionRepository repository;

    public RuleScreenConsumer(TransactionEventProducer producer, TransactionRepository repository) {
        this.producer = producer;
        this.repository = repository;
    }

    @KafkaListener(topics = KafkaTopics.INGESTED, groupId = "rule-screen", concurrency = "${core.kafka.screening-concurrency:3}")
    public void onIngested(TransactionEvent event) {
        List<String> reasons = screen(event);

        if (reasons.isEmpty()) {
            updateStatus(event, TransactionStatus.CLEARED_EARLY);
            log.info("Cleared early [tx={}, account={}]", event.transactionId(), event.accountId());
            return;
        }

        String reason = String.join("; ", reasons);
        updateStatus(event, TransactionStatus.FLAGGED);
        producer.publish(KafkaTopics.FLAGGED, event.flagged(reason));
        log.info("Flagged [tx={}, account={}]: {}", event.transactionId(), event.accountId(), reason);
    }

    private List<String> screen(TransactionEvent event) {
        List<String> reasons = new ArrayList<>();
        BigDecimal amount = event.amount();

        if (amount.compareTo(STRUCTURING_FLOOR) >= 0 && amount.compareTo(REPORTING_THRESHOLD) < 0) {
            reasons.add("possible structuring (amount just under reporting threshold)");
        }
        if (amount.compareTo(LARGE_AMOUNT) >= 0) {
            reasons.add("large amount over 50000");
        }
        String country = event.counterpartyCountry();
        if (country != null && HIGH_RISK_COUNTRIES.contains(country)) {
            reasons.add("high-risk counterparty country: " + country);
        }

        return reasons;
    }

    private void updateStatus(TransactionEvent event, TransactionStatus status) {
        repository.findById(event.transactionId()).ifPresent(tx -> {
            tx.setStatus(status);
            repository.save(tx);
        });
    }
}
