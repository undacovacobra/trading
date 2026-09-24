"""System events: the operational audit trail and the dashboard notification feed.

Other channels (push, email, Discord, Telegram) can subscribe here later.
"""

import logging

from sqlalchemy.orm import Session

from app.models import RiskEvent, SystemEvent

log = logging.getLogger("options_bridge.events")

_LEVELS = {"INFO": logging.INFO, "WARN": logging.WARNING, "ERROR": logging.ERROR, "CRITICAL": logging.CRITICAL}


class Notifier:
    def __init__(self, clock):
        self.clock = clock

    def emit(
        self,
        session: Session,
        level: str,
        code: str,
        message: str,
        *,
        event_id: str | None = None,
        trade_uid: str | None = None,
        details: dict | None = None,
    ) -> SystemEvent:
        ev = SystemEvent(
            at=self.clock.now(),
            level=level,
            code=code,
            message=message,
            event_id=event_id,
            trade_uid=trade_uid,
            details=details,
        )
        session.add(ev)
        log.log(_LEVELS.get(level, logging.INFO), "%s %s%s", code, message, f" event={event_id}" if event_id else "")
        return ev

    def risk(
        self,
        session: Session,
        code: str,
        message: str,
        *,
        mode: str | None,
        strategy_id: str | None,
        event_id: str | None,
        details: dict | None = None,
    ) -> None:
        session.add(
            RiskEvent(
                at=self.clock.now(),
                code=code,
                message=message,
                mode=mode,
                strategy_id=strategy_id,
                event_id=event_id,
                details=details,
            )
        )
        self.emit(session, "WARN", "RISK_LIMIT_REACHED" if code.startswith("MAX_") else code, message, event_id=event_id)
