"""Simulated brokerage for OBSERVE mode.

This module has no access to any Robinhood trading client -- it is constructed
with nothing but a clock and the paper-account settings. OBSERVE mode therefore
cannot reach order placement, regardless of any flag or UI state.

Fill model (section 17): buy at ASK + entry slippage, sell at BID - exit slippage.
The quote used is the one captured at signal time and is stored with the fill.
"""

import uuid

from sqlalchemy.orm import Session

from app.executors.base import ExecutionResult, Executor
from app.models import Fill, LedgerType, Order, OrderStatus, Trade
from app.schemas.config import SystemMode
from app.schemas.trade_intent import ExitIntent, TradeIntent
from app.services import config_store, paper_account
from app.services.position_sizer import CONTRACT_MULTIPLIER
from app.services.state_machine import TradeState, transition


class PaperExecutor(Executor):
    mode = SystemMode.OBSERVE.value
    buying_power_code = "PAPER_CASH_INSUFFICIENT"

    def __init__(self, clock):
        self.clock = clock

    def _account(self, session: Session):
        system = config_store.get_system(session)
        return paper_account.get_active_account(session, starting_balance=system.paper_starting_balance, now=self.clock.now())

    async def get_account_equity(self, session: Session) -> float:
        return paper_account.summary(session, self._account(session))["equity"]

    async def get_buying_power(self, session: Session) -> float:
        return paper_account.cash(session, self._account(session).id)

    async def get_position(self, session: Session, trade: Trade) -> dict:
        return {"trade_uid": trade.trade_uid, "state": trade.state, "quantity": trade.quantity, "simulated": True}

    def account_label(self) -> str:
        return "Paper account"

    async def open_position(self, session: Session, trade: Trade, intent: TradeIntent) -> ExecutionResult:
        now = self.clock.now()
        acct = self._account(session)
        fill = round(intent.limit_price, 4)
        cost = round(fill * CONTRACT_MULTIPLIER * intent.quantity, 2)
        fees = intent.estimated_fees
        available = paper_account.cash(session, acct.id)
        if cost + fees > available + 1e-9:
            msg = f"needs {cost + fees:.2f}, paper cash {available:.2f}"
            trade.reject_code, trade.reject_message = "PAPER_CASH_INSUFFICIENT", msg
            transition(session, trade, TradeState.REJECTED, now, msg)
            return ExecutionResult(False, error_code="PAPER_CASH_INSUFFICIENT", error_message=msg)

        order = Order(
            trade=trade,
            client_order_id=f"paper-{uuid.uuid4().hex[:16]}",
            mode=self.mode,
            side="BUY_TO_OPEN",
            order_type="SIMULATED",
            contract_id=intent.contract_id,
            requested_quantity=intent.quantity,
            limit_price=intent.limit_price,
            status=OrderStatus.SIMULATED_FILLED,
            filled_quantity=intent.quantity,
            avg_fill_price=fill,
            bid_at_request=intent.estimated_bid,
            ask_at_request=intent.estimated_ask,
            mid_at_request=intent.estimated_mid,
            quote_time=intent.quote_time,
            slippage_assumption=intent.entry_slippage,
            fees=fees,
            created_at=now,
            submitted_at=now,
            filled_at=now,
        )
        session.add(order)
        session.add(Fill(order=order, quantity=intent.quantity, price=fill, at=now, simulated=True))

        trade.paper_account_id = acct.id
        trade.entry_fill = fill
        trade.entry_timestamp = now
        trade.gross_cost = cost
        trade.fees = fees
        trade.current_bid, trade.current_ask, trade.current_quote_time = intent.estimated_bid, intent.estimated_ask, intent.quote_time
        session.flush()
        paper_account.add_ledger(
            session, acct, LedgerType.OPTION_PURCHASE, -cost, now, trade_id=trade.id,
            description=f"BUY {intent.quantity} {intent.option_symbol} @ {fill:.2f}",
        )
        if fees:
            paper_account.add_ledger(session, acct, LedgerType.FEE, -fees, now, trade_id=trade.id, description="Entry fees")

        transition(session, trade, TradeState.SIMULATED_ENTRY, now, f"paper buy {intent.quantity} @ {fill:.2f}")
        transition(session, trade, TradeState.OPEN, now)
        paper_account.snapshot(session, acct, now)
        return ExecutionResult(True, filled_quantity=intent.quantity, fill_price=fill)

    async def close_position(self, session: Session, trade: Trade, intent: ExitIntent) -> ExecutionResult:
        now = self.clock.now()
        acct = self._account(session)
        if trade.paper_account_id != acct.id:
            # Position belongs to a retired paper account (cannot happen: reset requires flat).
            return ExecutionResult(False, error_code="PAPER_ACCOUNT_MISMATCH", error_message="trade not in active paper account")
        qty = trade.quantity
        fill = round(intent.limit_price, 4)
        proceeds = round(fill * CONTRACT_MULTIPLIER * qty, 2)
        exit_fees = round(intent.fee_per_contract * qty, 2)

        transition(session, trade, TradeState.SIMULATED_EXIT, now, f"paper sell {qty} @ {fill:.2f} ({intent.reason})")
        order = Order(
            trade=trade,
            client_order_id=f"paper-{uuid.uuid4().hex[:16]}",
            mode=self.mode,
            side="SELL_TO_CLOSE",
            order_type="SIMULATED",
            contract_id=intent.contract_id,
            requested_quantity=qty,
            limit_price=intent.limit_price,
            status=OrderStatus.SIMULATED_FILLED,
            filled_quantity=qty,
            avg_fill_price=fill,
            bid_at_request=intent.bid,
            ask_at_request=intent.ask,
            mid_at_request=intent.mid,
            quote_time=intent.quote_time,
            slippage_assumption=intent.exit_slippage,
            fees=exit_fees,
            created_at=now,
            submitted_at=now,
            filled_at=now,
        )
        session.add(order)
        session.add(Fill(order=order, quantity=qty, price=fill, at=now, simulated=True))
        paper_account.add_ledger(
            session, acct, LedgerType.OPTION_SALE, proceeds, now, trade_id=trade.id,
            description=f"SELL {qty} {trade.option_symbol} @ {fill:.2f}",
        )
        if exit_fees:
            paper_account.add_ledger(session, acct, LedgerType.FEE, -exit_fees, now, trade_id=trade.id, description="Exit fees")

        trade.exit_bid, trade.exit_ask, trade.exit_mid = intent.bid, intent.ask, intent.mid
        trade.exit_quote_time = intent.quote_time
        trade.exit_slippage = intent.exit_slippage
        trade.exit_fill = fill
        trade.exit_timestamp = now
        trade.gross_proceeds = proceeds
        trade.fees = round((trade.fees or 0) + exit_fees, 2)
        trade.realized_pnl = round(proceeds - trade.gross_cost - trade.fees, 2)
        basis = trade.gross_cost + (trade.fees - exit_fees)
        trade.return_percent = round(trade.realized_pnl / basis * 100, 2) if basis else None
        trade.current_bid, trade.current_ask, trade.current_quote_time = intent.bid, intent.ask, intent.quote_time
        transition(session, trade, TradeState.CLOSED, now)
        session.flush()
        paper_account.snapshot(session, acct, now)
        return ExecutionResult(True, filled_quantity=qty, fill_price=fill)
