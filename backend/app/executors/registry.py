"""Picks the executor for a mode.

OBSERVE -> PaperExecutor (no broker order capability exists in its object graph)
DRY_RUN -> DryRunExecutor
LIVE    -> RobinhoodExecutor, only present after build_live_executor() succeeded
"""

from app.executors.base import Executor
from app.executors.dry_run import DryRunExecutor
from app.executors.paper import PaperExecutor
from app.schemas.config import SystemMode


class ExecutorUnavailable(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class ExecutorRegistry:
    def __init__(self, paper: PaperExecutor, dry_run: DryRunExecutor):
        self.paper = paper
        self.dry_run = dry_run
        self._live: Executor | None = None

    @property
    def live(self) -> Executor | None:
        return self._live

    def attach_live(self, executor: Executor) -> None:
        if executor.mode != SystemMode.LIVE.value:
            raise ValueError("attach_live expects a LIVE executor")
        self._live = executor

    def detach_live(self) -> None:
        self._live = None

    def get(self, mode: str) -> Executor:
        if mode == SystemMode.OBSERVE.value:
            return self.paper
        if mode == SystemMode.DRY_RUN.value:
            return self.dry_run
        if mode == SystemMode.LIVE.value:
            if self._live is None:
                raise ExecutorUnavailable("LIVE_EXECUTOR_UNAVAILABLE", "LIVE executor is not connected")
            return self._live
        raise ExecutorUnavailable("UNKNOWN_MODE", f"Unknown mode {mode}")
