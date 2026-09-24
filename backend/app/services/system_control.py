"""Mode switching, startup safety and reconciliation."""

import logging

from sqlalchemy import func, select

from app.executors.robinhood import LiveTradingUnavailable, build_live_executor
from app.models import Trade
from app.schemas.config import SystemMode
from app.services import config_store, paper_account
from app.services.state_machine import HOLDING_STATES

log = logging.getLogger("options_bridge.control")

LIVE_CONFIRMATION = "ENABLE LIVE TRADING"


class ControlError(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def open_count(s, mode: str) -> int:
    return s.scalar(select(func.count(Trade.id)).where(Trade.mode == mode, Trade.state.in_(HOLDING_STATES)))


class SystemControl:
    def __init__(self, env, engine, clock, notifier):
        self.env = env
        self.engine = engine
        self.clock = clock
        self.notifier = notifier

    async def startup(self) -> None:
        """Safest restart behaviour: never silently come back trading LIVE."""
        with self.engine.sessions() as s:
            system = config_store.get_system(s)
            paper_account.get_active_account(s, starting_balance=system.paper_starting_balance, now=self.clock.now())
            if system.mode == SystemMode.LIVE:
                live_open = open_count(s, SystemMode.LIVE.value)
                try:
                    executor = await build_live_executor(self.env, self.clock)
                except LiveTradingUnavailable as e:
                    system.mode = SystemMode.OBSERVE
                    msg = f"Restarted: LIVE unavailable ({e.code}); switched to OBSERVE"
                    if live_open:
                        system.out_of_sync = True
                        system.out_of_sync_reason = f"{live_open} LIVE position(s) open but LIVE executor unavailable"
                        msg += f". {live_open} LIVE position(s) must be managed manually in Robinhood."
                    self.notifier.emit(s, "CRITICAL", "LIVE_UNAVAILABLE_ON_RESTART", msg)
                else:
                    if live_open or self.env.live_resume_on_restart:
                        self.engine.executors.attach_live(executor)
                        if not self.env.live_resume_on_restart:
                            system.paused = True
                            self.notifier.emit(s, "WARN", "RESTART_PAUSED", "Restarted in LIVE with open positions: new entries PAUSED, exits enabled")
                    else:
                        await executor.client.close()
                        system.mode = SystemMode.OBSERVE
                        self.notifier.emit(s, "WARN", "RESTART_OBSERVE", "Restarted: LIVE -> OBSERVE (set LIVE_RESUME_ON_RESTART to change)")
                config_store.save_system(s, system)
            self.notifier.emit(s, "INFO", "STARTUP", f"Started in {system.mode.value}{' (PAUSED)' if system.paused else ''}")
            s.commit()

    async def set_mode(self, mode: SystemMode, confirmation: str | None) -> None:
        async with self.engine.lock:
            with self.engine.sessions() as s:
                system = config_store.get_system(s)
                if mode == system.mode:
                    return
                if system.mode == SystemMode.LIVE and open_count(s, SystemMode.LIVE.value):
                    raise ControlError("LIVE_POSITIONS_OPEN", "Close LIVE positions before leaving LIVE mode (use PAUSE to stop new entries)")
                if mode == SystemMode.LIVE:
                    if confirmation != LIVE_CONFIRMATION:
                        raise ControlError("CONFIRMATION_REQUIRED", f'Type "{LIVE_CONFIRMATION}" to enable LIVE trading')
                    if system.out_of_sync:
                        raise ControlError("SYSTEM_OUT_OF_SYNC", "Resolve the out-of-sync condition first")
                    try:
                        executor = await build_live_executor(self.env, self.clock)
                    except LiveTradingUnavailable as e:
                        raise ControlError(e.code, e.message) from e
                    self.engine.executors.attach_live(executor)
                    system.live_confirmed_at = self.clock.now().isoformat()
                else:
                    live = self.engine.executors.live
                    self.engine.executors.detach_live()
                    if live is not None:
                        await live.client.close()
                previous = system.mode
                system.mode = mode
                config_store.save_system(s, system)
                self.notifier.emit(s, "CRITICAL" if mode == SystemMode.LIVE else "WARN", "MODE_CHANGED", f"{previous.value} -> {mode.value}")
                s.commit()

    async def set_paused(self, paused: bool) -> None:
        async with self.engine.lock:
            with self.engine.sessions() as s:
                system = config_store.get_system(s)
                system.paused = paused
                config_store.save_system(s, system)
                self.notifier.emit(s, "WARN", "PAUSED" if paused else "RESUMED", "New entries paused" if paused else "New entries resumed")
                s.commit()

    async def clear_out_of_sync(self) -> None:
        async with self.engine.lock:
            with self.engine.sessions() as s:
                system = config_store.get_system(s)
                problems = self._paper_problems(s)
                if problems:
                    raise ControlError("PAPER_INCONSISTENT", "; ".join(problems))
                system.out_of_sync, system.out_of_sync_reason = False, None
                config_store.save_system(s, system)
                self.notifier.emit(s, "WARN", "OUT_OF_SYNC_CLEARED", "Out-of-sync flag cleared by operator")
                s.commit()

    def _paper_problems(self, s) -> list[str]:
        system = config_store.get_system(s)
        acct = paper_account.get_active_account(s, starting_balance=system.paper_starting_balance, now=self.clock.now())
        return paper_account.check_consistency(s, acct)

    async def reconcile(self) -> dict:
        """Paper: internal consistency. LIVE: broker positions vs local (when mapped)."""
        async with self.engine.lock:
            with self.engine.sessions() as s:
                problems = self._paper_problems(s)
                live_result = None
                live = self.engine.executors.live
                if live is not None:
                    live_result = await self._reconcile_live(s, live)
                    problems += live_result.get("problems", [])
                if problems:
                    system = config_store.get_system(s)
                    if not system.out_of_sync:
                        system.out_of_sync = True
                        system.out_of_sync_reason = problems[0]
                        config_store.save_system(s, system)
                        self.notifier.emit(s, "CRITICAL", "SYSTEM_OUT_OF_SYNC", "; ".join(problems))
                s.commit()
                return {"ok": not problems, "problems": problems, "live": live_result}

    async def _reconcile_live(self, s, live) -> dict:
        local = {
            t.contract_id: t.quantity
            for t in s.scalars(select(Trade).where(Trade.mode == SystemMode.LIVE.value, Trade.state.in_(HOLDING_STATES)))
        }
        try:
            broker = await live.client.get_option_positions()
        except Exception as e:  # noqa: BLE001
            return {"problems": [f"could not fetch Robinhood positions: {e}"]}
        broker_qty = {p["contract_id"]: int(p["quantity"]) for p in broker}
        problems = []
        for cid, qty in local.items():
            if broker_qty.get(cid, 0) < qty:
                problems.append(f"local position {cid} x{qty} not found at broker (broker has {broker_qty.get(cid, 0)})")
        # Contracts at the broker that we don't own are NOT touched (manual trades are allowed).
        return {"problems": problems, "local": local, "broker": broker_qty}
