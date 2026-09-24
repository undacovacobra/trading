"""Dashboard-editable configuration. Validated by pydantic, stored as JSON in the DB."""

from datetime import time
from enum import Enum
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class SystemMode(str, Enum):
    OBSERVE = "OBSERVE"
    DRY_RUN = "DRY_RUN"
    LIVE = "LIVE"


class ExpirationMode(str, Enum):
    SAME_DAY = "SAME_DAY"  # 0DTE; skip if no expiration today
    NEXT_AVAILABLE = "NEXT_AVAILABLE"  # nearest listed expiration (today or later)
    EXACT_DTE = "EXACT_DTE"  # exactly `dte` trading sessions out (1 = next trading day)
    MIN_DTE = "MIN_DTE"  # nearest expiration at least `dte` trading sessions out


class StrikeMode(str, Enum):
    ATM = "ATM"
    DELTA = "DELTA"
    PREMIUM = "PREMIUM"
    OTM_STRIKES = "OTM_STRIKES"  # N listed strikes out of the money (negative = ITM)
    OTM_PERCENT = "OTM_PERCENT"  # X% out of the money (negative = ITM)
    FIXED_OFFSET = "FIXED_OFFSET"  # $ offset from underlying in the OTM direction


class SizingMode(str, Enum):
    FIXED_DOLLARS = "FIXED_DOLLARS"
    FIXED_CONTRACTS = "FIXED_CONTRACTS"
    PERCENT_EQUITY = "PERCENT_EQUITY"


class FallbackPolicy(str, Enum):
    NONE = "NONE"  # skip the trade if the selected contract fails filters
    NEAREST_PASSING = "NEAREST_PASSING"  # allow the next-best ranked contract, up to N steps away


class OppositeEntryPolicy(str, Enum):
    REJECT = "REJECT"
    REVERSE = "REVERSE"


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", use_enum_values=False)


def _parse_hhmm(v: str) -> str:
    time.fromisoformat(v)
    return v


class ContractFilters(_Strict):
    min_bid: float = Field(0.10, ge=0)
    max_ask: float = Field(20.00, gt=0)
    max_spread_pct: float = Field(10.0, gt=0, le=100)
    min_open_interest: int | None = Field(None, ge=0)
    min_volume: int | None = Field(None, ge=0)


class StrategyConfig(_Strict):
    enabled: bool = True
    # None = use the global futures->options symbol map.
    options_underlying: str | None = None
    long_option_type: Literal["CALL", "PUT"] = "CALL"
    short_option_type: Literal["CALL", "PUT"] = "PUT"

    expiration_mode: ExpirationMode = ExpirationMode.SAME_DAY
    dte: int = Field(0, ge=0, le=60)

    strike_mode: StrikeMode = StrikeMode.ATM
    target_delta: float = Field(0.50, gt=0, lt=1)
    target_premium: float = Field(3.00, gt=0)
    otm_strikes: int = Field(1, ge=-20, le=20)
    otm_percent: float = Field(0.5, ge=-20, le=20)
    strike_offset: float = Field(1.0, ge=-100, le=100)
    fallback_policy: FallbackPolicy = FallbackPolicy.NONE
    fallback_max_steps: int = Field(1, ge=1, le=10)

    filters: ContractFilters = Field(default_factory=ContractFilters)

    sizing_mode: SizingMode = SizingMode.FIXED_DOLLARS
    position_size_dollars: float = Field(1000.0, gt=0)
    fixed_contracts: int = Field(1, ge=1)
    percent_equity: float = Field(5.0, gt=0, le=100)
    max_contracts: int = Field(10, ge=1)

    opposite_entry_policy: OppositeEntryPolicy = OppositeEntryPolicy.REJECT
    reverse_when_flat: bool = True  # REVERSE_* with no position open -> just open the new side

    @field_validator("options_underlying")
    @classmethod
    def _upper(cls, v):
        return v.strip().upper() or None if isinstance(v, str) else v


class RiskConfig(_Strict):
    max_trade_cost: float = Field(1000.0, gt=0)
    max_contracts_per_trade: int = Field(10, ge=1)
    max_open_positions: int = Field(1, ge=1)
    max_daily_loss: float = Field(2000.0, gt=0)
    max_daily_trades: int = Field(10, ge=1)
    max_consecutive_losses: int = Field(5, ge=1)
    max_total_exposure: float = Field(5000.0, gt=0)
    max_spread_pct: float = Field(15.0, gt=0, le=100)
    # America/New_York wall-clock times. Shifted earlier automatically on early-close days.
    trading_start: str = "09:35"
    trading_end: str = "15:50"
    latest_0dte_entry: str = "15:30"
    entry_slippage: float = Field(0.01, ge=0)
    exit_slippage: float = Field(0.01, ge=0)
    fee_per_contract: float = Field(0.0, ge=0)

    @field_validator("trading_start", "trading_end", "latest_0dte_entry")
    @classmethod
    def _hhmm(cls, v: str) -> str:
        return _parse_hhmm(v)

    @model_validator(mode="after")
    def _window(self):
        if time.fromisoformat(self.trading_start) >= time.fromisoformat(self.trading_end):
            raise ValueError("trading_start must be before trading_end")
        return self


DEFAULT_SYMBOL_MAP = {"NQ": "QQQ", "MNQ": "QQQ", "ES": "SPY", "MES": "SPY", "RTY": "IWM", "M2K": "IWM"}


class SystemSettings(_Strict):
    mode: SystemMode = SystemMode.OBSERVE
    paused: bool = False
    out_of_sync: bool = False
    out_of_sync_reason: str | None = None
    live_confirmed_at: str | None = None
    paper_starting_balance: float = Field(25_000.0, gt=0)
    quote_interval_seconds: int = Field(10, ge=2, le=300)
    auto_register_strategies: bool = True
    symbol_map: dict[str, str] = Field(default_factory=lambda: dict(DEFAULT_SYMBOL_MAP))

    @field_validator("symbol_map")
    @classmethod
    def _upper_map(cls, v: dict[str, str]):
        return {k.strip().upper(): val.strip().upper() for k, val in v.items() if k.strip() and val.strip()}
