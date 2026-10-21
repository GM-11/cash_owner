package com.protrader.cash_owner.grpc;

import com.protrader.cash_owner.config.CashOwnerProperties;
import com.protrader.proto.exchange.v1.AccountUpdate;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class AccountUpdateBus {

    private static final Logger log = LoggerFactory.getLogger(AccountUpdateBus.class);

    private final Map<ServerCallStreamObserver<AccountUpdate>, String> gateways = new ConcurrentHashMap<>();
    private final KafkaTemplate<String, byte[]> kafka;
    private final String topic;

    public AccountUpdateBus(KafkaTemplate<String, byte[]> kafka, CashOwnerProperties props) {
        this.kafka = kafka;
        this.topic = props.kafka().accountUpdatesTopic();
    }

    public void subscribe(String gatewayId, ServerCallStreamObserver<AccountUpdate> stream) {
        if (gatewayId.isEmpty()) {
            stream.onError(Status.INVALID_ARGUMENT.withDescription("gateway_id is required").asRuntimeException());
            return;
        }
        stream.setOnCancelHandler(() -> {
            gateways.remove(stream);
            log.info("account-updates: gateway {} disconnected", gatewayId);
        });
        gateways.put(stream, gatewayId);
        log.info("account-updates: gateway {} subscribed ({} connected)", gatewayId, gateways.size());
    }

    public void publish(AccountUpdate update) {
        kafka.send(topic, Long.toString(update.getAccountId()), update.toByteArray()).whenComplete((r, ex) -> {
            if (ex != null) {
                log.error("account-updates: kafka send failed for account {} seq {}: {}", update.getAccountId(),
                        update.getAccountSeq(), ex.toString(), ex);
            }
        });

        for (var e : gateways.entrySet()) {
            ServerCallStreamObserver<AccountUpdate> stream = e.getKey();
            synchronized (stream) { // a StreamObserver must not be called from two threads at once
                try {
                    stream.onNext(update);
                } catch (RuntimeException ex) { // stream already closed
                    gateways.remove(stream);
                    log.warn("account-updates: dropped gateway {}: {}", e.getValue(), ex.toString());
                }
            }
        }
    }

    public int connectedGateways() {
        return gateways.size();
    }
}
