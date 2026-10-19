package com.protrader.cash_owner.grpc;

import com.protrader.proto.exchange.v1.AccountSnapshot;
import com.protrader.proto.exchange.v1.AccountUpdate;
import com.protrader.proto.exchange.v1.AccountUpdatesRequest;
import com.protrader.proto.exchange.v1.CancelOrderAck;
import com.protrader.proto.exchange.v1.CancelOrderRequest;
import com.protrader.proto.exchange.v1.CashOwnerGrpc;
import com.protrader.proto.exchange.v1.GetAccountRequest;
import com.protrader.proto.exchange.v1.GetOrdersRequest;
import com.protrader.proto.exchange.v1.GetOrdersResponse;
import com.protrader.proto.exchange.v1.ModifyOrderAck;
import com.protrader.proto.exchange.v1.ModifyOrderRequest;
import com.protrader.proto.exchange.v1.PlaceOrderAck;
import com.protrader.proto.exchange.v1.PlaceOrderRequest;
import com.protrader.proto.exchange.v1.TransferAck;
import com.protrader.proto.exchange.v1.TransferRequest;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class CashOwnerService extends CashOwnerGrpc.CashOwnerImplBase {

    private final AccountUpdateBus updates;

    public CashOwnerService(AccountUpdateBus updates) {
        this.updates = updates;
    }

    @Override
    public void placeOrder(PlaceOrderRequest request, StreamObserver<PlaceOrderAck> out) {
        notBuilt(out, "PlaceOrder");
    }

    @Override
    public void cancelOrder(CancelOrderRequest request, StreamObserver<CancelOrderAck> out) {
        notBuilt(out, "CancelOrder");
    }

    @Override
    public void modifyOrder(ModifyOrderRequest request, StreamObserver<ModifyOrderAck> out) {
        notBuilt(out, "ModifyOrder"); // [later] in the contract
    }

    @Override
    public void getAccount(GetAccountRequest request, StreamObserver<AccountSnapshot> out) {
        notBuilt(out, "GetAccount");
    }

    @Override
    public void getOrders(GetOrdersRequest request, StreamObserver<GetOrdersResponse> out) {
        notBuilt(out, "GetOrders");
    }

    @Override
    public void deposit(TransferRequest request, StreamObserver<TransferAck> out) {
        notBuilt(out, "Deposit");
    }

    @Override
    public void withdraw(TransferRequest request, StreamObserver<TransferAck> out) {
        notBuilt(out, "Withdraw");
    }

    @Override
    public void subscribeAccountUpdates(AccountUpdatesRequest request, StreamObserver<AccountUpdate> out) {
        updates.subscribe(request.getGatewayId(), (ServerCallStreamObserver<AccountUpdate>) out);
    }

    private static void notBuilt(StreamObserver<?> out, String rpc) {
        out.onError(Status.UNIMPLEMENTED.withDescription(rpc + " is not built yet").asRuntimeException());
    }
}
