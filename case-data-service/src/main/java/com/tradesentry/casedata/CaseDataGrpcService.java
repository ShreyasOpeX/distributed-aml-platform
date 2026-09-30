package com.tradesentry.casedata;

import com.tradesentry.proto.casedata.AccountHistoryRequest;
import com.tradesentry.proto.casedata.AccountHistoryResponse;
import com.tradesentry.proto.casedata.CaseDataServiceGrpc;
import com.tradesentry.proto.casedata.SimilarCase;
import com.tradesentry.proto.casedata.SimilarCasesRequest;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * gRPC implementation of the case-data contract. Spring gRPC auto-registers any bean of type
 * {@link io.grpc.BindableService} (which the generated base class implements) with the server.
 *
 * <p>Data is synthetic and deterministic. The implementation intentionally honours
 * request bounds so the client can increase investigation depth without silently
 * receiving the same amount of evidence as the first pass.
 */
@Service
public class CaseDataGrpcService extends CaseDataServiceGrpc.CaseDataServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(CaseDataGrpcService.class);
    private static final int MAX_SIMILAR_CASES = 10;

    @Override
    public void getAccountHistory(AccountHistoryRequest request,
                                  StreamObserver<AccountHistoryResponse> responseObserver) {
        String accountId = request.getAccountId();
        int hash = Math.abs(accountId.hashCode());
        int lookbackDays = Math.max(1, request.getLookbackDays());

        String riskBand = switch (hash % 3) {
            case 0 -> "LOW";
            case 1 -> "MEDIUM";
            default -> "HIGH";
        };
        boolean hasPriorSar = (hash % 5 == 0);

        int baselineTransactions = 50 + hash % 200;
        int periods = Math.max(1, (lookbackDays + 89) / 90);
        int totalTransactions = baselineTransactions * periods;
        double avgTransactionAmount = 1000 + hash % 5000;
        double maxTransactionAmount = 20000 + hash % 80000;
        int priorFlags = Math.min(20, (hash % 6) * periods);

        AccountHistoryResponse response = AccountHistoryResponse.newBuilder()
                .setAccountId(accountId)
                .setTotalTransactions(totalTransactions)
                .setAvgTransactionAmount(avgTransactionAmount)
                .setMaxTransactionAmount(maxTransactionAmount)
                .setPriorFlags(priorFlags)
                .setHasPriorSar(hasPriorSar)
                .setRiskBand(riskBand)
                .build();

        log.info("getAccountHistory [account={}, lookback={}] -> band={} priorSar={} transactions={} flags={}",
                accountId, lookbackDays, riskBand, hasPriorSar, totalTransactions, priorFlags);

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void retrieveSimilarCases(SimilarCasesRequest request,
                                     StreamObserver<SimilarCase> responseObserver) {
        int max = Math.max(1, Math.min(request.getMaxResults(), MAX_SIMILAR_CASES));
        boolean risky = request.getAmount() > 20000
                || List.of("KP", "IR", "SY").contains(request.getCounterpartyCountry());
        int count = risky ? max : Math.max(1, max - 2);

        log.info("retrieveSimilarCases [amount={} country={}] streaming up to {}",
                request.getAmount(), request.getCounterpartyCountry(), count);

        for (int i = 0; i < count; i++) {
            String outcome = risky ? (i % 2 == 0 ? "SAR_FILED" : "ESCALATED") : "CLEARED";
            String caseId = "case-" + (1000 + i);
            double similarityScore = Math.max(0.50, 0.95 - i * 0.07);
            String summary = "Prior " + outcome + " case with comparable profile";

            SimilarCase similarCase = SimilarCase.newBuilder()
                    .setCaseId(caseId)
                    .setSimilarityScore(similarityScore)
                    .setOutcome(outcome)
                    .setSummary(summary)
                    .build();

            responseObserver.onNext(similarCase);
        }

        responseObserver.onCompleted();
    }
}
