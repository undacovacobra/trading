"""Configuration and control endpoints (dashboard)."""

import uuid

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field

from app.api.security import require_dashboard_auth
from app.api.tradingview import ingest
from app.schemas.config import RiskConfig, StrategyConfig, SystemMode
from app.services import config_store, paper_account
from app.services.system_control import ControlError

router = APIRouter(prefix="/api", dependencies=[Depends(require_dashboard_auth)])


def _control_error(e: ControlError):
    raise HTTPException(409, {"code": e.code, "message": e.message})


# ---------------------------------------------------------------- config
@router.get("/config")
async def get_config(request: Request):
    with request.app.state.sessions() as s:
        s_ = config_store.get_system(s)
        return {
            "system": s_.model_dump(mode="json"),
            "risk": config_store.get_risk(s).model_dump(mode="json"),
            "template": config_store.get_template(s).model_dump(mode="json"),
            "strategies": {k: v.model_dump(mode="json") for k, v in config_store.list_strategies(s).items()},
        }


@router.put("/config/risk")
async def put_risk(request: Request, body: RiskConfig):
    st = request.app.state
    async with st.engine.lock:
        with st.sessions() as s:
            config_store.save_risk(s, body)
            st.notifier.emit(s, "INFO", "CONFIG_CHANGED", "Risk limits updated")
            s.commit()
    return body


@router.put("/config/strategies/{strategy_id}")
async def put_strategy(request: Request, strategy_id: str, body: StrategyConfig):
    if not 0 < len(strategy_id) <= 64:
        raise HTTPException(400, "Invalid strategy id")
    st = request.app.state
    async with st.engine.lock:
        with st.sessions() as s:
            config_store.save_strategy(s, strategy_id, body)
            st.notifier.emit(s, "INFO", "CONFIG_CHANGED", f"Strategy {strategy_id} updated")
            s.commit()
    return body


@router.put("/config/template")
async def put_template(request: Request, body: StrategyConfig):
    st = request.app.state
    async with st.engine.lock:
        with st.sessions() as s:
            config_store.save_template(s, body)
            s.commit()
    return body


class SystemConfigUpdate(BaseModel):
    paper_starting_balance: float | None = Field(None, gt=0)
    quote_interval_seconds: int | None = Field(None, ge=2, le=300)
    auto_register_strategies: bool | None = None
    symbol_map: dict[str, str] | None = None


@router.put("/config/system")
async def put_system(request: Request, body: SystemConfigUpdate):
    """Mode, pause and out-of-sync are NOT settable here; use the control endpoints."""
    st = request.app.state
    async with st.engine.lock:
        with st.sessions() as s:
            system = config_store.get_system(s)
            data = system.model_dump()
            data.update(body.model_dump(exclude_none=True))
            system = type(system).model_validate(data)
            config_store.save_system(s, system)
            st.notifier.emit(s, "INFO", "CONFIG_CHANGED", "System settings updated")
            s.commit()
            return system.model_dump(mode="json")


# ---------------------------------------------------------------- controls
class ModeRequest(BaseModel):
    mode: SystemMode
    confirmation: str | None = None


class Confirm(BaseModel):
    confirmation: str


@router.post("/control/mode")
async def set_mode(request: Request, body: ModeRequest):
    try:
        await request.app.state.control.set_mode(body.mode, body.confirmation)
    except ControlError as e:
        _control_error(e)
    return {"mode": body.mode.value}


@router.post("/control/pause")
async def pause(request: Request):
    await request.app.state.control.set_paused(True)
    return {"paused": True}


@router.post("/control/resume")
async def resume(request: Request):
    await request.app.state.control.set_paused(False)
    return {"paused": False}


@router.post("/control/emergency-close")
async def emergency_close(request: Request, body: Confirm):
    if body.confirmation != "CLOSE":
        raise HTTPException(400, 'Type "CLOSE" to confirm')
    return await request.app.state.engine.emergency_close()


@router.post("/control/reconcile")
async def reconcile(request: Request):
    return await request.app.state.control.reconcile()


@router.post("/control/clear-out-of-sync")
async def clear_out_of_sync(request: Request, body: Confirm):
    if body.confirmation != "CLEAR":
        raise HTTPException(400, 'Type "CLEAR" to confirm')
    try:
        await request.app.state.control.clear_out_of_sync()
    except ControlError as e:
        _control_error(e)
    return {"out_of_sync": False}


class ResetRequest(BaseModel):
    confirmation: str
    starting_balance: float | None = Field(None, gt=0)


@router.post("/control/paper-reset")
async def paper_reset(request: Request, body: ResetRequest):
    if body.confirmation != "RESET":
        raise HTTPException(400, 'Type "RESET" to confirm')
    st = request.app.state
    async with st.engine.lock:
        with st.sessions() as s:
            system = config_store.get_system(s)
            if body.starting_balance:
                system.paper_starting_balance = body.starting_balance
                config_store.save_system(s, system)
            try:
                acct = paper_account.reset_account(s, starting_balance=system.paper_starting_balance, now=st.clock.now())
            except ValueError as e:
                raise HTTPException(409, str(e)) from e
            st.notifier.emit(s, "WARN", "PAPER_RESET", f"Paper account reset to ${system.paper_starting_balance:,.2f}")
            s.commit()
            return {"account_id": acct.id, "starting_balance": acct.starting_balance}


class TestSignal(BaseModel):
    action: str
    strategy_id: str = config_store.DEFAULT_STRATEGY_ID
    symbol: str = "NQ1!"
    reason: str | None = None
    entry_price: float | None = None
    stop_price: float | None = None
    target_price: float | None = None
    futures_price: float | None = None
    trade_id: str | None = None
    signal_type: str | None = None


@router.post("/test-signal")
async def test_signal(request: Request, body: TestSignal):
    """Inject a signal from the dashboard. Refused in LIVE mode."""
    st = request.app.state
    with st.sessions() as s:
        if config_store.get_system(s).mode == SystemMode.LIVE:
            raise HTTPException(409, "Test signals are disabled in LIVE mode")
    data = body.model_dump(exclude_none=True, exclude={"signal_type"})
    data.update(
        secret="dashboard",
        event_id=f"dash-{uuid.uuid4().hex[:12]}",
        signal_time=st.clock.now().isoformat(),
        metadata={"signal_type": body.signal_type or "manual_test"},
    )
    return await ingest(request, data, source="dashboard", ip="dashboard")
