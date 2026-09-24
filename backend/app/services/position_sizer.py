import math
from dataclasses import dataclass, field

from app.schemas.config import RiskConfig, SizingMode, StrategyConfig

CONTRACT_MULTIPLIER = 100


@dataclass
class SizingResult:
    quantity: int
    unit_cost: float  # per contract incl. fees
    estimated_cost: float  # premium only
    estimated_fees: float
    budget: float | None
    notes: list[str] = field(default_factory=list)


def size_position(cfg: StrategyConfig, risk: RiskConfig, limit_price: float, equity: float) -> SizingResult:
    """Contracts to buy at `limit_price`. Never exceeds the requested dollar amount,
    the strategy/global contract caps, or the global max trade cost."""
    unit = limit_price * CONTRACT_MULTIPLIER + risk.fee_per_contract
    notes: list[str] = []
    budget = None
    if unit <= 0:
        return SizingResult(0, unit, 0.0, 0.0, None, ["non-positive price"])

    if cfg.sizing_mode == SizingMode.FIXED_CONTRACTS:
        qty = cfg.fixed_contracts
    else:
        if cfg.sizing_mode == SizingMode.FIXED_DOLLARS:
            budget = cfg.position_size_dollars
        else:
            budget = max(0.0, equity) * cfg.percent_equity / 100
        qty = math.floor(budget / unit + 1e-9)

    caps = {
        "strategy max_contracts": cfg.max_contracts,
        "global max_contracts_per_trade": risk.max_contracts_per_trade,
        "global max_trade_cost": math.floor(risk.max_trade_cost / unit + 1e-9),
    }
    for name, cap in caps.items():
        if qty > cap:
            notes.append(f"quantity capped {qty} -> {cap} by {name}")
            qty = cap
    qty = max(0, qty)
    cost = round(qty * limit_price * CONTRACT_MULTIPLIER, 2)
    fees = round(qty * risk.fee_per_contract, 2)
    return SizingResult(qty, unit, cost, fees, budget, notes)
