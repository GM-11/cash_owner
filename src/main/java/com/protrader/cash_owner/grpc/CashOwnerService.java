package com.protrader.cash_owner.grpc;

import com.protrader.cash_owner.orders.PlaceOrderFlow;
import com.protrader.proto.exchange.v1.AccountSnapshot;
import com.protrader.proto.exchange.v1.AccountUpdate;
import com.protrader.proto.exchange.v1.AccountUpdatesRequest;
import com.protrader.proto.exchange.v1.CancelOrderAck;
import com.protrader.proto.exchange.v1.CancelOrderRequest;
import com.protrader.proto.exchange.v1.CashOwnerGrpc;
import com.protrader.proto.exchange.v1.CashRejectReason;
import com.protrader.proto.exchange.v1.GetAccountRequest;
import com.protrader.proto.exchange.v1.GetOrdersRequest;
import com.protrader.proto.exchange.v1.GetOrdersResponse;
import com.protrader.proto.exchange.v1.ModifyOrderAck;
import com.protrader.proto.exchange.v1.ModifyOrderRequest;
import com.protrader.proto.exchange.v1.OrderType;
import com.protrader.proto.exchange.v1.PlaceOrderAck;
import com.protrader.proto.exchange.v1.PlaceOrderRequest;
import com.protrader.proto.exchange.v1.Side;
import com.protrader.proto.exchange.v1.TimeInForce;
import com.protrader.proto.exchange.v1.TransferAck;
import com.protrader.proto.exchange.v1.TransferRequest;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.regex.Pattern;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class CashOwnerService extends CashOwnerGrpc.CashOwnerImplBase {

    private static final Pattern CLIENT_ORDER_ID = Pattern.compile("^[A-Za-z0-9_-]{1,48}$");

    private final AccountUpdateBus updates;
    private final PlaceOrderFlow placeOrderFlow;

    public CashOwnerService(AccountUpdateBus updates, PlaceOrderFlow placeOrderFlow) {
        this.updates = updates;
        this.placeOrderFlow = placeOrderFlow;
    }

    @Override
    public void placeOrder(PlaceOrderRequest request, StreamObserver<PlaceOrderAck> out) {
        String invalid = invalidField(request);
        if (invalid != null) {
            out.onError(Status.INVALID_ARGUMENT.withDescription(invalid).asRuntimeException());
            return;
        }
        // limit + GTC only for now; UNSPECIFIED means the default (LIMIT / GTC)
        boolean limit = request.getType() == OrderType.ORDER_TYPE_UNSPECIFIED
                || request.getType() == OrderType.ORDER_TYPE_LIMIT;
        boolean gtc = request.getTimeInForce() == TimeInForce.TIME_IN_FORCE_UNSPECIFIED
                || request.getTimeInForce() == TimeInForce.TIME_IN_FORCE_GTC;
        if (!limit || !gtc || request.hasStopPriceMinor()) {
            out.onNext(PlaceOrderAck.newBuilder().setAccepted(false)
                    .setReason(CashRejectReason.CASH_REJECT_REASON_NOT_SUPPORTED).build());
            out.onCompleted();
            return;
        }
        placeOrderFlow.place(request, out);
    }

    private static String invalidField(PlaceOrderRequest r) {
        if (r.getAccountId() == 0) {
            return "account_id is required";
        }
        if (!CLIENT_ORDER_ID.matcher(r.getClientOrderId()).matches()) {
            return "client_order_id must be 1-48 chars of [A-Za-z0-9_-]";
        }
        if (r.getSymbol() == 0) {
            return "symbol is required";
        }
        if (r.getSide() != Side.SIDE_BUY && r.getSide() != Side.SIDE_SELL) {
            return "side must be BUY or SELL";
        }
        if (r.getPriceMinor() <= 0) {
            return "price_minor must be > 0";
        }
        if (r.getQuantity() <= 0) {
            return "quantity must be > 0";
        }
        if (r.getGatewayId().isEmpty()) {
            return "gateway_id is required";
        }
        return null;
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
