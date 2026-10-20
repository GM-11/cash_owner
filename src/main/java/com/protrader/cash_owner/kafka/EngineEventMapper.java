package com.protrader.cash_owner.kafka;

import com.protrader.cash_owner.models.EngineEvent;
import com.protrader.cash_owner.models.EngineEventKind;
import com.protrader.cash_owner.models.Side;
import com.protrader.proto.exchange.v1.MarketEvent;

public final class EngineEventMapper {

    private EngineEventMapper() {
    }

    public static EngineEvent fromProto(MarketEvent m) {
        return new EngineEvent(
                m.getSymbol(),
                m.getSeq(),
                kind(m),
                m.getTs(),
                m.getOrderId(),
                m.getPassiveId(),
                m.getOwnerId(),
                m.getOtherOwner(),
                side(m),
                m.hasPrice() ? m.getPrice() : null,
                m.getQuantity(),
                m.getClientOrderId());
    }

    private static EngineEventKind kind(MarketEvent m) {
        return switch (m.getKind()) {
            case EVENT_KIND_ACCEPTED -> EngineEventKind.ACCEPTED;
            case EVENT_KIND_RESTED -> EngineEventKind.RESTED;
            case EVENT_KIND_TRADE -> EngineEventKind.TRADE;
            case EVENT_KIND_CANCELLED -> EngineEventKind.CANCELLED;
            case EVENT_KIND_EXPIRED -> EngineEventKind.EXPIRED;
            case EVENT_KIND_REJECTED -> EngineEventKind.REJECTED;
            case EVENT_KIND_HALTED -> EngineEventKind.HALTED;
            case EVENT_KIND_UNSPECIFIED, UNRECOGNIZED ->
                throw new IllegalArgumentException("engine event without a known kind: " + m);
            default -> EngineEventKind.OTHER; // MODIFIED, REPLACED, STOP_*, RESUMED
        };
    }

    private static Side side(MarketEvent m) {
        return switch (m.getSide()) {
            case SIDE_BUY -> Side.BUY;
            case SIDE_SELL -> Side.SELL;
            default -> {
                if (m.getKind() == com.protrader.proto.exchange.v1.EventKind.EVENT_KIND_TRADE) {
                    throw new IllegalArgumentException("TRADE without a side: " + m);
                }
                yield null;
            }
        };
    }
}
