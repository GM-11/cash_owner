package com.protrader.cash_owner;

import com.protrader.proto.exchange.v1.SequencerGrpc;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.grpc.client.ImportGrpcClients;

@SpringBootApplication
@ConfigurationPropertiesScan
@ImportGrpcClients(target = "sequencer", types = SequencerGrpc.SequencerStub.class)
public class CashOwnerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CashOwnerApplication.class, args);
    }

}
