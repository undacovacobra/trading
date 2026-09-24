"""POST /api/webhooks/tradingview

Authenticate -> validate -> store (idempotent on event_id) -> return 200 at once.
Trading work happens in the background worker; the response never waits for a
broker (TradingView cancels requests after ~3 seconds).
"""

import hmac
import json
import logging

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from pydantic import ValidationError
from sqlalchemy.exc import IntegrityError

from app.api.security import TRADINGVIEW_IPS, BodyTooLarge, client_ip, is_https, read_limited_body
from app.models import WebhookEvent
from app.schemas.tradingview import SUPPORTED_ACTIONS, TradingViewSignal
from app.services.notifier import Notifier

log = logging.getLogger("options_bridge.webhook")
router = APIRouter()


def _reject(request: Request, status: int, code: str, message: str, ip: str, event_id: str | None = None) -> JSONResponse:
    state = request.app.state
    log.warning("webhook rejected %s from %s: %s", code, ip, message)
    try:
        with state.sessions() as s:
            Notifier(state.clock).emit(s, "WARN", f"WEBHOOK_{code}", f"{message} (from {ip})", event_id=event_id)
            s.commit()
    except Exception:  # noqa: BLE001 -- logging a rejection must never break the response
        log.exception("could not record rejected webhook")
    return JSONResponse({"status": "rejected", "code": code, "message": message}, status_code=status)


async def ingest(request: Request, data: dict, *, source: str, ip: str) -> JSONResponse:
    """Validate and store a signal payload (already authenticated)."""
    state = request.app.state
    action = str(data.get("action", "")).strip().upper()
    if action not in SUPPORTED_ACTIONS:
        return _reject(request, 422, "UNSUPPORTED_SIGNAL", f"Unsupported action {action!r}", ip, data.get("event_id"))
    try:
        sig = TradingViewSignal.model_validate(data)
    except ValidationError as e:
        errors = "; ".join(f"{'.'.join(map(str, err['loc']))}: {err['msg']}" for err in e.errors())
        return _reject(request, 422, "MALFORMED_SIGNAL", errors, ip, data.get("event_id") if isinstance(data.get("event_id"), str) else None)

    received = state.clock.now()
    ev = WebhookEvent(
        event_id=sig.event_id,
        source=source,
        strategy_id=sig.strategy_id,
        symbol=sig.symbol,
        action=sig.action.value,
        tv_trade_id=sig.trade_id,
        signal_time=sig.signal_time,
        received_at=received,
        source_ip=ip,
        payload=sig.public_payload(),
    )
    with state.sessions() as s:
        s.add(ev)
        try:
            s.commit()
        except IntegrityError:
            s.rollback()
            existing = s.query(WebhookEvent).filter_by(event_id=sig.event_id).one()
            existing.duplicate_count += 1
            s.commit()
            log.info("duplicate event %s ignored (seen %d times)", sig.event_id, existing.duplicate_count + 1)
            return JSONResponse({"status": "duplicate", "event_id": sig.event_id})
        pk = ev.id
    log.info("webhook stored %s %s %s", sig.event_id, sig.action.value, sig.symbol)
    state.worker.enqueue(pk)
    return JSONResponse({"status": "accepted", "event_id": sig.event_id})


@router.post("/api/webhooks/tradingview")
async def tradingview_webhook(request: Request):
    env = request.app.state.env
    ip = client_ip(request, env.trust_proxy_headers)
    if env.require_https and not is_https(request, env.trust_proxy_headers):
        return _reject(request, 403, "HTTPS_REQUIRED", "Webhook must be sent over HTTPS", ip)
    if env.tradingview_ip_allowlist and ip not in TRADINGVIEW_IPS:
        return _reject(request, 403, "IP_NOT_ALLOWED", "Source IP not in TradingView allowlist", ip)
    if not request.app.state.rate_limiter.allow(ip):
        log.warning("webhook rate limited %s", ip)
        return JSONResponse({"status": "rejected", "code": "RATE_LIMITED"}, status_code=429)
    try:
        body = await read_limited_body(request, env.webhook_max_body_bytes)
    except BodyTooLarge:
        return _reject(request, 413, "BODY_TOO_LARGE", f"Body exceeds {env.webhook_max_body_bytes} bytes", ip)
    try:
        data = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return _reject(request, 400, "MALFORMED_JSON", "Body is not valid JSON", ip)
    if not isinstance(data, dict):
        return _reject(request, 400, "MALFORMED_JSON", "JSON body must be an object", ip)

    secret = data.get("secret")
    if not isinstance(secret, str) or not hmac.compare_digest(secret.encode(), env.webhook_secret.encode()):
        return _reject(request, 401, "BAD_SECRET", "Invalid webhook secret", ip)
    return await ingest(request, data, source="tradingview", ip=ip)
