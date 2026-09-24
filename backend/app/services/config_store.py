"""Typed access to the dashboard-editable configuration stored in the DB."""

from datetime import datetime, timezone

from sqlalchemy.orm import Session

from app.models import Setting, Strategy
from app.schemas.config import RiskConfig, StrategyConfig, SystemSettings

KEY_SYSTEM = "system"
KEY_RISK = "risk"
KEY_TEMPLATE = "strategy_template"

DEFAULT_STRATEGY_ID = "reversal_v6"


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _get(session: Session, key: str) -> dict | None:
    row = session.get(Setting, key)
    return dict(row.value) if row else None


def _put(session: Session, key: str, value: dict) -> None:
    row = session.get(Setting, key)
    if row is None:
        session.add(Setting(key=key, value=value, updated_at=_now()))
    else:
        row.value = value
        row.updated_at = _now()


def get_system(session: Session) -> SystemSettings:
    return SystemSettings.model_validate(_get(session, KEY_SYSTEM) or {})


def save_system(session: Session, s: SystemSettings) -> None:
    _put(session, KEY_SYSTEM, s.model_dump(mode="json"))


def get_risk(session: Session) -> RiskConfig:
    return RiskConfig.model_validate(_get(session, KEY_RISK) or {})


def save_risk(session: Session, r: RiskConfig) -> None:
    _put(session, KEY_RISK, r.model_dump(mode="json"))


def get_template(session: Session) -> StrategyConfig:
    return StrategyConfig.model_validate(_get(session, KEY_TEMPLATE) or {})


def save_template(session: Session, c: StrategyConfig) -> None:
    _put(session, KEY_TEMPLATE, c.model_dump(mode="json"))


def get_strategy(session: Session, strategy_id: str, *, auto_register: bool = False) -> StrategyConfig | None:
    row = session.get(Strategy, strategy_id)
    if row is None:
        if not auto_register:
            return None
        cfg = get_template(session)
        save_strategy(session, strategy_id, cfg)
        return cfg
    cfg = StrategyConfig.model_validate(row.config)
    cfg.enabled = row.enabled
    return cfg


def save_strategy(session: Session, strategy_id: str, cfg: StrategyConfig) -> None:
    row = session.get(Strategy, strategy_id)
    data = cfg.model_dump(mode="json")
    if row is None:
        session.add(Strategy(strategy_id=strategy_id, enabled=cfg.enabled, config=data, created_at=_now(), updated_at=_now()))
    else:
        row.enabled = cfg.enabled
        row.config = data
        row.updated_at = _now()
    session.flush()


def list_strategies(session: Session) -> dict[str, StrategyConfig]:
    out = {}
    for row in session.query(Strategy).order_by(Strategy.strategy_id):
        cfg = StrategyConfig.model_validate(row.config)
        cfg.enabled = row.enabled
        out[row.strategy_id] = cfg
    return out


def seed_defaults(session: Session) -> None:
    if _get(session, KEY_SYSTEM) is None:
        save_system(session, SystemSettings())
    if _get(session, KEY_RISK) is None:
        save_risk(session, RiskConfig())
    if _get(session, KEY_TEMPLATE) is None:
        save_template(session, StrategyConfig())
    if session.query(Strategy).count() == 0:
        save_strategy(session, DEFAULT_STRATEGY_ID, StrategyConfig())
    session.commit()


def resolve_underlying(system: SystemSettings, cfg: StrategyConfig, futures_root: str) -> str | None:
    if cfg.options_underlying:
        return cfg.options_underlying
    return system.symbol_map.get(futures_root)
