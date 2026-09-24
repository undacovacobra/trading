"""Robinhood Agentic Trading MCP client.

Two client classes, split on purpose:

* RobinhoodReadClient     -- can only call read-only tools (chains, instruments,
                             quotes, positions, order history). Used by the market
                             data provider in every mode, including OBSERVE.
* RobinhoodTradingClient  -- additionally allowed to call review/place/cancel.
                             It can only be constructed with a LiveAuthorization,
                             which only executors/robinhood.build_live_executor()
                             creates, and only when LIVE_TRADING=true.

Parameter formats are NOT assumed. The spec is explicit: inspect the real MCP
schemas before mapping arguments. Run

    python -m app.brokers.robinhood_mcp inspect --out robinhood_tools.json

against your connected Agentic account, then implement the `_map_*` methods
below from the dumped schemas. Until then every mapping raises SchemaNotVerified.
"""

import argparse
import asyncio
import json
import logging
import os
from contextlib import AsyncExitStack
from typing import Any

log = logging.getLogger(__name__)

READ_ONLY_TOOLS = frozenset(
    {
        "get_option_chains",
        "get_option_instruments",
        "get_option_quotes",
        "get_option_positions",
        "get_option_orders",
    }
)
ORDER_TOOLS = frozenset({"review_option_order", "place_option_order", "cancel_option_order"})


class SchemaNotVerified(RuntimeError):
    code = "ROBINHOOD_SCHEMA_NOT_MAPPED"


class ToolNotPermitted(PermissionError):
    code = "TOOL_NOT_PERMITTED"


class RobinhoodUnavailable(RuntimeError):
    code = "ROBINHOOD_UNAVAILABLE"


class LiveAuthorization:
    """Capability token. Only the live-executor factory creates one."""

    __slots__ = ("reason",)

    def __init__(self, reason: str, _key: object):
        if _key is not _LIVE_KEY:
            raise ToolNotPermitted("LiveAuthorization can only be issued by the live executor factory")
        self.reason = reason


_LIVE_KEY = object()


def _issue_live_authorization(reason: str) -> LiveAuthorization:
    return LiveAuthorization(reason, _LIVE_KEY)


def _extra_read_tools() -> frozenset[str]:
    """Additional read-only tool names (e.g. account info) discovered via `inspect`."""
    raw = os.environ.get("ROBINHOOD_MCP_EXTRA_READ_TOOLS", "")
    names = {n.strip() for n in raw.split(",") if n.strip()}
    bad = names & ORDER_TOOLS or {n for n in names if "place" in n or "cancel" in n}
    if bad:
        raise ToolNotPermitted(f"Order tools cannot be registered as read-only: {sorted(bad)}")
    return frozenset(names)


class _MCPConnection:
    """Thin wrapper around the MCP streamable-HTTP client."""

    def __init__(self, url: str, token: str | None):
        self.url = url
        self.token = token
        self._stack: AsyncExitStack | None = None
        self._session = None

    async def connect(self):
        if self._session is not None:
            return self._session
        try:
            import httpx
            from mcp import ClientSession
            from mcp.client.streamable_http import streamable_http_client
        except ImportError as e:  # pragma: no cover
            raise RobinhoodUnavailable(f"MCP client library not installed: {e}") from e
        headers = {"Authorization": f"Bearer {self.token}"} if self.token else {}
        self._stack = AsyncExitStack()
        try:
            http = await self._stack.enter_async_context(httpx.AsyncClient(headers=headers, timeout=30))
            streams = await self._stack.enter_async_context(streamable_http_client(self.url, http_client=http))
            read, write = streams[0], streams[1]
            self._session = await self._stack.enter_async_context(ClientSession(read, write))
            await self._session.initialize()
        except Exception as e:
            await self.close()
            raise RobinhoodUnavailable(f"Could not connect to Robinhood MCP: {e}") from e
        return self._session

    async def list_tools(self) -> list[dict]:
        session = await self.connect()
        result = await session.list_tools()
        return [t.model_dump(mode="json") for t in result.tools]

    async def call_tool(self, name: str, arguments: dict) -> Any:
        session = await self.connect()
        return await session.call_tool(name, arguments)

    async def close(self):
        if self._stack is not None:
            await self._stack.aclose()
        self._stack = None
        self._session = None


class RobinhoodReadClient:
    def __init__(self, url: str | None, token: str | None):
        if not url:
            raise RobinhoodUnavailable("ROBINHOOD_MCP_URL is not configured")
        self._conn = _MCPConnection(url, token)
        self._allowed = READ_ONLY_TOOLS | _extra_read_tools()

    async def call_tool(self, name: str, arguments: dict) -> Any:
        if name not in self._allowed:
            log.critical("Blocked MCP call to non-permitted tool %s", name)
            raise ToolNotPermitted(f"{type(self).__name__} may not call {name}")
        log.info("robinhood mcp call %s", name)
        return await self._conn.call_tool(name, arguments)

    async def list_tools(self) -> list[dict]:
        return await self._conn.list_tools()

    async def close(self):
        await self._conn.close()

    # -- read mappings: fill these in from the inspected schemas -------------
    async def get_account(self) -> dict:
        raise SchemaNotVerified("Map the Agentic account lookup tool after running `inspect`")

    async def get_option_positions(self) -> list[dict]:
        raise SchemaNotVerified("Map get_option_positions after running `inspect`")

    async def get_option_orders(self) -> list[dict]:
        raise SchemaNotVerified("Map get_option_orders after running `inspect`")


class RobinhoodTradingClient(RobinhoodReadClient):
    def __init__(self, url: str | None, token: str | None, authorization: LiveAuthorization):
        if not isinstance(authorization, LiveAuthorization):
            raise ToolNotPermitted("RobinhoodTradingClient requires a LiveAuthorization")
        super().__init__(url, token)
        self._allowed = self._allowed | ORDER_TOOLS
        self.authorization = authorization

    # Normalised inputs; map to the real schema once inspected.
    async def review_option_order(
        self, *, contract_id: str, side: str, quantity: int, limit_price: float, client_order_id: str
    ) -> dict:
        raise SchemaNotVerified("Map review_option_order after running `inspect`")

    async def place_option_order(
        self, *, contract_id: str, side: str, quantity: int, limit_price: float, client_order_id: str
    ) -> dict:
        raise SchemaNotVerified("Map place_option_order after running `inspect`")

    async def get_option_order(self, broker_order_id: str) -> dict:
        raise SchemaNotVerified("Map order-status lookup after running `inspect`")

    async def cancel_option_order(self, broker_order_id: str) -> dict:
        raise SchemaNotVerified("Map cancel_option_order after running `inspect`")


async def _inspect(out: str) -> None:
    client = RobinhoodReadClient(os.environ.get("ROBINHOOD_MCP_URL"), os.environ.get("ROBINHOOD_MCP_TOKEN"))
    try:
        tools = await client.list_tools()
    finally:
        await client.close()
    with open(out, "w") as f:
        json.dump(tools, f, indent=2)
    print(f"Wrote {len(tools)} tool schemas to {out}")
    for t in tools:
        kind = "ORDER" if t["name"] in ORDER_TOOLS else ("read" if t["name"] in READ_ONLY_TOOLS else "other")
        print(f"  [{kind:5}] {t['name']}")


def main() -> None:
    p = argparse.ArgumentParser(description="Robinhood Agentic MCP utilities")
    sub = p.add_subparsers(dest="cmd", required=True)
    ins = sub.add_parser("inspect", help="Dump the MCP tool schemas (read-only; places no orders)")
    ins.add_argument("--out", default="robinhood_tools.json")
    args = p.parse_args()
    if args.cmd == "inspect":
        asyncio.run(_inspect(args.out))


if __name__ == "__main__":
    main()
