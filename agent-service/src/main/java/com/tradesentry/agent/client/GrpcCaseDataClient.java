package com.tradesentry.agent.client;

import com.tradesentry.proto.casedata.AccountHistoryRequest;
import com.tradesentry.proto.casedata.AccountHistoryResponse;
import com.tradesentry.proto.casedata.CaseDataServiceGrpc;
import com.tradesentry.proto.casedata.SimilarCasesRequest;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Real gRPC-backed implementation of {@link CaseDataClient}. It talks to case-data-service over
 * the named channel "case-data"; swapping this in for the Phase 5 stub required no change to the
 * graph, nodes, or interface.
 */
@Component
public class GrpcCaseDataClient implements CaseDataClient {

    private final CaseDataServiceGrpc.CaseDataServiceBlockingStub stub;
    private final long deadlineMs;

    public GrpcCaseDataClient(GrpcChannelFactory channels,
                               org.springframework.core.env.Environment environment) {
        this.deadlineMs = environment.getProperty("agent.grpc.deadline-ms", Long.class, 2000L);
        this.stub = CaseDataServiceGrpc.newBlockingStub(channels.createChannel("case-data"));
    }

    @Override
    public AccountHistory getAccountHistory(String accountId, int lookbackDays) {
        AccountHistoryRequest request = AccountHistoryRequest.newBuilder()
                .setAccountId(accountId)
                .setLookbackDays(lookbackDays)
                .build();

        AccountHistoryResponse response = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS).getAccountHistory(request);

        return new AccountHistory(
                response.getAccountId(),
                response.getTotalTransactions(),
                response.getAvgTransactionAmount(),
                response.getMaxTransactionAmount(),
                response.getPriorFlags(),
                response.getHasPriorSar(),
                response.getRiskBand());
    }

    @Override
    public List<SimilarCase> retrieveSimilarCases(String summary, BigDecimal amount,
                                                  String counterpartyCountry, int maxResults) {
        SimilarCasesRequest request = SimilarCasesRequest.newBuilder()
                .setTransactionSummary(summary)
                .setAmount(amount.doubleValue())
                .setCounterpartyCountry(counterpartyCountry == null ? "" : counterpartyCountry)
                .setMaxResults(maxResults)
                .build();

        // Server-streaming call: the blocking stub returns an Iterator that blocks per element
        // until the server closes the stream. Drain it into a plain list for the caller.
        Iterator<com.tradesentry.proto.casedata.SimilarCase> it =
                stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS).retrieveSimilarCases(request);
        List<SimilarCase> cases = new ArrayList<>();
        while (it.hasNext()) {
            com.tradesentry.proto.casedata.SimilarCase c = it.next();
            cases.add(new SimilarCase(
                    c.getCaseId(),
                    c.getSimilarityScore(),
                    c.getOutcome(),
                    c.getSummary()));
        }
        return cases;
    }
}
