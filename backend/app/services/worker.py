"""Background processing of stored webhook events.

The webhook handler only stores the event and returns 200; this worker does the
trading work. Events live in the database, so they survive restarts:

* RECEIVED   -> re-queued on startup
* PROCESSING -> the process died mid-event. Paper work is a single DB
  transaction, so if no trade row references the event nothing happened and it
  is safely re-queued. If a trade row does reference it (live work commits
  before contacting the broker), it is marked INTERRUPTED and the system goes
  OUT OF SYNC instead of risking a duplicate order.
"""

import asyncio
import logging

from sqlalchemy import or_, select

from app.models import EventStatus, Trade, WebhookEvent
from app.services import config_store

log = logging.getLogger("options_bridge.worker")


class EventWorker:
    def __init__(self, engine, sessions, notifier, clock):
        self.engine = engine
        self.sessions = sessions
        self.notifier = notifier
        self.clock = clock
        self.queue: asyncio.Queue[int] | None = None
        self._task: asyncio.Task | None = None

    def recover(self) -> list[int]:
        pending: list[int] = []
        with self.sessions() as s:
            for ev in s.scalars(
                select(WebhookEvent)
                .where(WebhookEvent.status.in_([EventStatus.RECEIVED, EventStatus.PROCESSING]))
                .order_by(WebhookEvent.id)
            ):
                if ev.status == EventStatus.PROCESSING:
                    linked = s.scalar(
                        select(Trade.id).where(or_(Trade.source_event_id == ev.event_id, Trade.exit_event_id == ev.event_id))
                    )
                    if linked is not None:
                        ev.status = EventStatus.INTERRUPTED
                        ev.result_code = "INTERRUPTED"
                        ev.result_message = "Process stopped mid-event after recording trade state; not re-run"
                        system = config_store.get_system(s)
                        system.out_of_sync = True
                        system.out_of_sync_reason = f"event {ev.event_id} was interrupted mid-execution; verify broker state"
                        config_store.save_system(s, system)
                        self.notifier.emit(s, "CRITICAL", "SYSTEM_OUT_OF_SYNC", system.out_of_sync_reason, event_id=ev.event_id)
                        continue
                    ev.status = EventStatus.RECEIVED
                pending.append(ev.id)
            s.commit()
        if pending:
            log.info("recovered %d pending event(s)", len(pending))
        return pending

    async def start(self) -> None:
        self.queue = asyncio.Queue()
        for pk in self.recover():
            self.queue.put_nowait(pk)
        self._task = asyncio.create_task(self._run(), name="event-worker")

    def enqueue(self, event_pk: int) -> None:
        if self.queue is not None:
            self.queue.put_nowait(event_pk)

    async def _run(self) -> None:
        assert self.queue is not None
        while True:
            pk = await self.queue.get()
            try:
                await self.engine.process_event(pk)
            except Exception:  # noqa: BLE001 -- the engine records failures; keep the worker alive
                log.exception("worker failed on event pk=%s", pk)
            finally:
                self.queue.task_done()

    async def wait_idle(self) -> None:
        if self.queue is not None:
            await self.queue.join()

    def pending(self) -> int:
        return self.queue.qsize() if self.queue is not None else 0

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
