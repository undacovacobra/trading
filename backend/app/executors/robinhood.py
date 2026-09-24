"""LIVE execution through the Robinhood Agentic Trading account (Phase 4).

Workflow per spec: review -> place (marketable limit) -> verify fill. The
broker-specific argument mapping lives in RobinhoodTradingClient and must be
written from the inspected MCP schemas; until then build_live_executor()
refuses to start and LIVE mode cannot be enabled.
"""

import asyncio
import logging
import uuid

from sqlalchemy.orm import Session

from app.brokers.robinhood_mcp import (
    RobinhoodTradingClient,
    SchemaNotVerified,
    _issue_live_authorization,
)
from app.config.settings import EnvSettings
from app.executors.base import ExecutionResult, Executor
from app.models import Order, OrderStatus, Trade
from app.schemas.config import SystemMode
from app.schemas.trade_intent import ExitIntent, TradeIntent
from app.services.position_sizer import CONTRACT_MULTIPLIER
from app.services.state_machine import TradeState, transition

log = logging.getLogger("options_bridge.live")


class LiveTradingUnavailable(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class RobinhoodExecutor(Executor):
    mode = SystemMode.LIVE.value
    buying_power_code = "INSUFFICIENT_BUYING_POWER"
    fill_timeout_seconds = 20.0

    def __init__(self, clock, client: RobinhoodTradingClient, account: dict):
        self.clock = clock
        self.client = client
        self.account = account

    def account_label(self) -> str:
        return f"Robinhood Agentic {self.account.get('display', self.account.get('id', '?'))}"

    async def get_account_equity(self, session: Session) -> float:
        acct = await self.client.get_account()
        return float(acct["equity"])

    async def get_buying_power(self, session: Session) -> float:
        acct = await self.client.get_account()
        return float(acct["buying_power"])

    async def get_position(self, session: Session, trade: Trade) -> dict:
        positions = await self.client.get_option_positions()
        return next((p for p in positions if p.get("contract_id") == trade.contract_id), {})

    async def _submit_and_wait(self, session: Session, order: Order) -> dict:
        args = dict(
            contract_id=order.contract_id,
            side=order.side,
            quantity=order.requested_quantity,
            limit_price=order.limit_price,
            client_order_id=order.client_order_id,
        )
        review = await self.client.review_option_order(**args)
        if review.get("blocking_warnings"):
            raise LiveTradingUnavailable("ORDER_REVIEW_FAILED", "; ".join(review["blocking_warnings"]))
        # Persist before placing so a crash can never lead to a blind re-submit.
        order.status = OrderStatus.SUBMITTED
        order.submitted_at = self.clock.now()
        session.commit()
        placed = await self.client.place_option_order(**args)
        order.broker_order_id = placed["order_id"]
        order.raw_response = placed
        session.commit()
        deadline = self.clock.now().timestamp() + self.fill_timeout_seconds
        while True:
            status = await self.client.get_option_order(order.broker_order_id)
            if status["state"] in ("filled", "cancelled", "rejected") or self.clock.now().timestamp() > deadline:
                return status
            await asyncio.sleep(1.0)

    def _new_order(self, trade: Trade, side: str, contract_id: str, qty: int, limit: float, bid, ask, mid, quote_time, slip):
        return Order(
            trade=trade,
            client_order_id=f"live-{uuid.uuid4().hex[:16]}",
            mode=self.mode,
            side=side,
            order_type="LIMIT",
            contract_id=contract_id,
            requested_quantity=qty,
            limit_price=limit,
            status=OrderStatus.PENDING_REVIEW,
            bid_at_request=bid,
            ask_at_request=ask,
            mid_at_request=mid,
            quote_time=quote_time,
            slippage_assumption=slip,
            broker_account_ref=self.account.get("id"),
            created_at=self.clock.now(),
        )

    async def open_position(self, session: Session, trade: Trade, intent: TradeIntent) -> ExecutionResult:
        order = self._new_order(
            trade, "BUY_TO_OPEN", intent.contract_id, intent.quantity, intent.limit_price,
            intent.estimated_bid, intent.estimated_ask, intent.estimated_mid, intent.quote_time, intent.entry_slippage,
        )
        session.add(order)
        trade.broker_account_ref = self.account.get("id")
        transition(session, trade, TradeState.ORDER_SUBMITTED, self.clock.now())
        try:
            status = await self._submit_and_wait(session, order)
        except Exception as e:  # noqa: BLE001 -- every broker failure must land in a recorded state
            order.status, order.error = OrderStatus.ERROR, str(e)
            transition(session, trade, TradeState.ERROR, self.clock.now(), str(e))
            return ExecutionResult(False, error_code=getattr(e, "code", "ORDER_FAILED"), error_message=str(e))
        filled = int(status.get("filled_quantity", 0))
        order.filled_quantity = filled
        order.avg_fill_price = status.get("average_price")
        if filled == 0:
            order.status = OrderStatus.CANCELLED if status["state"] != "rejected" else OrderStatus.REJECTED
            if status["state"] not in ("cancelled", "rejected"):
                await self.client.cancel_option_order(order.broker_order_id)
                order.cancel_status = "REQUESTED"
            transition(session, trade, TradeState.REJECTED, self.clock.now(), f"not filled: {status['state']}")
            return ExecutionResult(False, error_code="ORDER_NOT_FILLED", error_message=status["state"])
        now = self.clock.now()
        order.status = OrderStatus.FILLED if filled == intent.quantity else OrderStatus.PARTIALLY_FILLED
        order.filled_at = now
        trade.quantity = filled
        trade.entry_fill = float(status["average_price"])
        trade.entry_timestamp = now
        trade.gross_cost = round(trade.entry_fill * CONTRACT_MULTIPLIER * filled, 2)
        if filled < intent.quantity:
            transition(session, trade, TradeState.PARTIALLY_FILLED, now, f"{filled}/{intent.quantity}")
            await self.client.cancel_option_order(order.broker_order_id)
            order.cancel_status = "REQUESTED"
        transition(session, trade, TradeState.OPEN, now)
        return ExecutionResult(True, filled_quantity=filled, fill_price=trade.entry_fill)

    async def close_position(self, session: Session, trade: Trade, intent: ExitIntent) -> ExecutionResult:
        transition(session, trade, TradeState.EXIT_REQUESTED, self.clock.now(), intent.reason)
        order = self._new_order(
            trade, "SELL_TO_CLOSE", trade.contract_id, trade.quantity, intent.limit_price,
            intent.bid, intent.ask, intent.mid, intent.quote_time, intent.exit_slippage,
        )
        session.add(order)
        transition(session, trade, TradeState.EXIT_ORDER_SUBMITTED, self.clock.now())
        try:
            status = await self._submit_and_wait(session, order)
        except Exception as e:  # noqa: BLE001
            order.status, order.error = OrderStatus.ERROR, str(e)
            transition(session, trade, TradeState.OPEN, self.clock.now(), f"exit failed: {e}")
            return ExecutionResult(False, error_code=getattr(e, "code", "EXIT_ORDER_FAILED"), error_message=str(e))
        filled = int(status.get("filled_quantity", 0))
        if filled < trade.quantity:
            order.status = OrderStatus.PARTIALLY_FILLED if filled else OrderStatus.CANCELLED
            transition(session, trade, TradeState.OPEN, self.clock.now(), f"exit filled {filled}/{trade.quantity}")
            return ExecutionResult(False, filled_quantity=filled, error_code="EXIT_NOT_FILLED", error_message=status["state"])
        now = self.clock.now()
        order.status, order.filled_quantity, order.avg_fill_price, order.filled_at = OrderStatus.FILLED, filled, status["average_price"], now
        trade.exit_bid, trade.exit_ask, trade.exit_mid, trade.exit_quote_time = intent.bid, intent.ask, intent.mid, intent.quote_time
        trade.exit_fill = float(status["average_price"])
        trade.exit_timestamp = now
        trade.gross_proceeds = round(trade.exit_fill * CONTRACT_MULTIPLIER * filled, 2)
        trade.realized_pnl = round(trade.gross_proceeds - trade.gross_cost - (trade.fees or 0), 2)
        trade.return_percent = round(trade.realized_pnl / trade.gross_cost * 100, 2) if trade.gross_cost else None
        transition(session, trade, TradeState.CLOSED, now)
        return ExecutionResult(True, filled_quantity=filled, fill_price=trade.exit_fill)


async def build_live_executor(env: EnvSettings, clock) -> RobinhoodExecutor:
    """The ONLY place a RobinhoodTradingClient (order-capable) is ever created."""
    if not env.live_trading:
        raise LiveTradingUnavailable("LIVE_DISABLED_BY_ENV", "LIVE_TRADING=true is not set in the server environment")
    if not env.robinhood_agentic_account:
        raise LiveTradingUnavailable("ACCOUNT_NOT_CONFIGURED", "ROBINHOOD_AGENTIC_ACCOUNT is not configured")
    try:
        client = RobinhoodTradingClient(
            env.robinhood_mcp_url, env.robinhood_mcp_token, _issue_live_authorization("LIVE mode enabled")
        )
        account = await client.get_account()
    except SchemaNotVerified as e:
        raise LiveTradingUnavailable(SchemaNotVerified.code, f"Robinhood adapter not mapped yet: {e}") from e
    except Exception as e:  # noqa: BLE001
        raise LiveTradingUnavailable(getattr(e, "code", "ROBINHOOD_UNAVAILABLE"), str(e)) from e
    # Refuse anything that is not the designated Agentic account.
    if account.get("id") != env.robinhood_agentic_account or not account.get("is_agentic", False):
        await client.close()
        raise LiveTradingUnavailable(
            "WRONG_ACCOUNT", f"Connected account {account.get('id')!r} is not the designated Agentic account"
        )
    log.warning("LIVE executor ready for account %s", account.get("id"))
    return RobinhoodExecutor(clock, client, account)
