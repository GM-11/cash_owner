package com.protrader.cash_owner.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.protrader.cash_owner.models.AccountChange;
import com.protrader.cash_owner.models.AccountChangeKind;
import com.protrader.cash_owner.models.TransferResult;
import com.protrader.cash_owner.models.views.BalancesView;
import com.protrader.cash_owner.models.views.TransferView;
import com.protrader.proto.exchange.v1.AccountUpdate;
import com.protrader.proto.exchange.v1.TransferAck;
import com.protrader.proto.exchange.v1.TransferStatus;

class ProtoMapperTransferTest {

    private static AccountChange transfer() {
        return new AccountChange(21, 4, AccountChangeKind.TRANSFER, null, 0, 0, 0, 5_555,
                new BalancesView(900, 40, 7), null, new TransferView("t1", false, 300));
    }

    @Test
    void givenATransferChange_whenMapped_thenTheCauseIsTransferWithBalancesAndTsAndNoOrderEventOrPositions() {
        AccountUpdate u = ProtoMapper.toAccountUpdate(transfer());

        assertEquals(AccountUpdate.CauseCase.TRANSFER, u.getCauseCase());
        assertEquals("t1", u.getTransfer().getTransferId());
        assertFalse(u.getTransfer().getDeposit());
        assertEquals(300, u.getTransfer().getAmountMinor());
        assertEquals(21, u.getAccountId());
        assertEquals(4, u.getAccountSeq());
        assertEquals(5_555, u.getTs());
        assertEquals(900, u.getBalances().getCashFreeMinor());
        assertEquals(40, u.getBalances().getCashLockedMinor());
        assertEquals(7, u.getBalances().getRealizedPnlMinor());
        assertFalse(u.hasOrderEvent());
        assertEquals(0, u.getPositionsCount());
    }

    @Test
    void givenTheTransferKind_whenMappedToAnOrderEventKind_thenItThrows() {
        assertThrows(IllegalArgumentException.class, () -> ProtoMapper.toProto(AccountChangeKind.TRANSFER));
    }

    @Test
    void givenEachTransferResult_whenMappedToAnAck_thenStatusAndNumbersMatch() {
        BalancesView b = new BalancesView(10, 20, 30);

        TransferAck applied = ProtoMapper.toTransferAck(new TransferResult.Applied(transfer()));
        assertEquals(TransferStatus.TRANSFER_STATUS_APPLIED, applied.getStatus());
        assertEquals(4, applied.getAccountSeq());
        assertEquals(900, applied.getBalances().getCashFreeMinor());

        TransferAck duplicate = ProtoMapper.toTransferAck(new TransferResult.Duplicate(b, 8));
        assertEquals(TransferStatus.TRANSFER_STATUS_DUPLICATE, duplicate.getStatus());
        assertEquals(8, duplicate.getAccountSeq());
        assertEquals(20, duplicate.getBalances().getCashLockedMinor());

        TransferAck insufficient = ProtoMapper.toTransferAck(new TransferResult.InsufficientCash(55, b, 9));
        assertEquals(TransferStatus.TRANSFER_STATUS_INSUFFICIENT_CASH, insufficient.getStatus());
        assertEquals(55, insufficient.getShortfallMinor());
        assertEquals(9, insufficient.getAccountSeq());

        TransferAck unknown = ProtoMapper.toTransferAck(new TransferResult.UnknownAccount());
        assertEquals(TransferStatus.TRANSFER_STATUS_UNKNOWN_ACCOUNT, unknown.getStatus());
        assertFalse(unknown.hasBalances());
        assertTrue(unknown.getAccountSeq() == 0);
    }
}
