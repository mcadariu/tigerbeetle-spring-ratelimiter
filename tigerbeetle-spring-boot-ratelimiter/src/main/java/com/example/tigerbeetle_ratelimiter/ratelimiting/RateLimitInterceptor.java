package com.example.tigerbeetle_ratelimiter.ratelimiting;

import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.Client;
import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.IdBatch;
import com.tigerbeetle.TransferBatch;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.trace.Span;
import jakarta.annotation.Nonnull;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Random;

import static com.example.tigerbeetle_ratelimiter.ratelimiting.RateLimiterConfigurer.OPERATOR_ID;
import static com.tigerbeetle.AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS;
import static com.tigerbeetle.CreateTransferResult.ExceedsCredits;
import static com.tigerbeetle.TransferFlags.PENDING;
import static io.micrometer.observation.Observation.Event.of;
import static io.micrometer.observation.Observation.start;
import static java.lang.String.valueOf;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

public class RateLimitInterceptor implements HandlerInterceptor {

    //this is to be retrieved from your authentication system
    public static final int USER_ID = new Random().nextInt();
    public static final int PER_REQUEST_DEDUCTION = 5;
    public static final int TIMEOUT_IN_SECONDS = 5;
    public static final int USER_CREDIT_INITIAL_AMOUNT = 10;

    private final Client client;
    private final ObservationRegistry observationRegistry;

    public RateLimitInterceptor(Client client, ObservationRegistry observationRegistry) {
        this.client = client;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public boolean preHandle(
            @Nonnull HttpServletRequest request, @Nonnull HttpServletResponse response, @Nonnull Object handler) {

        IdBatch idBatch = new IdBatch(1);
        idBatch.add(USER_ID);

        AccountBatch userAccount = client.lookupAccounts(idBatch);

        if (!userAccount.next()) {
            AccountBatch accountBatch = new AccountBatch(1);
            accountBatch.add();
            accountBatch.setId(USER_ID);
            accountBatch.setLedger(1);
            accountBatch.setCode(1);
            accountBatch.setFlags(DEBITS_MUST_NOT_EXCEED_CREDITS);

            client.createAccounts(accountBatch);

            makeTransfer(
                    USER_CREDIT_INITIAL_AMOUNT,
                    OPERATOR_ID,
                    USER_ID,
                    0,
                    0
            );
        }

        CreateTransferResultBatch transferErrors = makeTransfer(
                        PER_REQUEST_DEDUCTION,
                        USER_ID,
                        OPERATOR_ID,
                        TIMEOUT_IN_SECONDS,
                        PENDING
                );

        if (transferErrors.next() && transferErrors.getResult().equals(ExceedsCredits)) {
            Observation observation = start("ratelimit", observationRegistry);
            observation.event(of("limited"));
            observation.highCardinalityKeyValue(KeyValue.of("user", valueOf(USER_ID)));
            observation.stop();
            Span.current().setAttribute("user", valueOf(USER_ID));
            response.setStatus(TOO_MANY_REQUESTS.value());
            return false;
        }
        return true;
    }

    private CreateTransferResultBatch makeTransfer(long amount, long debitAcct, long creditAcct, int timeout, int flag) {
        TransferBatch transfer = new TransferBatch(1);
        transfer.add();
        transfer.setId(new Random().nextInt());
        transfer.setDebitAccountId(debitAcct);
        transfer.setCreditAccountId(creditAcct);
        transfer.setLedger(1);
        transfer.setCode(1);
        transfer.setAmount(amount);
        transfer.setFlags(flag);
        transfer.setTimeout(timeout);

        return client.createTransfers(transfer);
    }
}
