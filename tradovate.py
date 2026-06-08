import httpx
from datetime import datetime, timedelta
from typing import Literal


LIVE_URL = "https://live.tradovateapi.com/v1"
DEMO_URL = "https://demo.tradovateapi.com/v1"


class TradovateClient:
    def __init__(
        self,
        username: str,
        password: str,
        app_id: str,
        app_version: str,
        cid: str,
        secret: str,
        demo: bool = True,
    ):
        self.base_url = DEMO_URL if demo else LIVE_URL
        self.username = username
        self.password = password
        self.app_id = app_id
        self.app_version = app_version
        self.cid = cid
        self.secret = secret
        self._access_token: str | None = None
        self._token_expiry: datetime | None = None
        self._account_id: int | None = None
        self._account_spec: str | None = None

    async def _ensure_authenticated(self) -> None:
        if self._access_token and self._token_expiry and datetime.now() < self._token_expiry:
            return
        await self._authenticate()

    async def _authenticate(self) -> None:
        async with httpx.AsyncClient() as client:
            resp = await client.post(
                f"{self.base_url}/auth/accesstokenrequest",
                json={
                    "name": self.username,
                    "password": self.password,
                    "appId": self.app_id,
                    "appVersion": self.app_version,
                    "cid": int(self.cid),
                    "sec": self.secret,
                    "deviceId": "tradingview-webhook",
                },
                timeout=10,
            )
            resp.raise_for_status()
            data = resp.json()

        if "errorText" in data:
            raise RuntimeError(f"Tradovate auth failed: {data['errorText']}")

        self._access_token = data["accessToken"]
        # Tokens last 24h; refresh at 23h to be safe
        self._token_expiry = datetime.now() + timedelta(hours=23)
        await self._load_account()

    async def _load_account(self) -> None:
        headers = {"Authorization": f"Bearer {self._access_token}"}
        async with httpx.AsyncClient() as client:
            resp = await client.get(f"{self.base_url}/account/list", headers=headers, timeout=10)
            resp.raise_for_status()
            accounts = resp.json()

        if not accounts:
            raise RuntimeError("No Tradovate accounts found")

        account = accounts[0]
        self._account_id = account["id"]
        self._account_spec = account["name"]

    def _headers(self) -> dict:
        return {"Authorization": f"Bearer {self._access_token}"}

    async def place_market_order(
        self,
        symbol: str,
        action: Literal["Buy", "Sell"],
        quantity: int,
    ) -> dict:
        await self._ensure_authenticated()

        payload = {
            "accountSpec": self._account_spec,
            "accountId": self._account_id,
            "action": action,
            "symbol": symbol,
            "orderQty": quantity,
            "orderType": "Market",
            "isAutomated": True,
        }

        async with httpx.AsyncClient() as client:
            resp = await client.post(
                f"{self.base_url}/order/placeorder",
                json=payload,
                headers=self._headers(),
                timeout=10,
            )
            resp.raise_for_status()
            return resp.json()

    async def close_position(self, symbol: str, quantity: int) -> dict:
        """Close (liquidate) an open position by placing the opposing market order."""
        await self._ensure_authenticated()

        async with httpx.AsyncClient() as client:
            resp = await client.get(
                f"{self.base_url}/position/list",
                headers=self._headers(),
                timeout=10,
            )
            resp.raise_for_status()
            positions = resp.json()

        matching = [p for p in positions if p.get("contractId") and symbol in str(p.get("contractId", ""))]
        if not matching:
            # Try by fetching contract first then matching
            pass

        # Liquidate via dedicated endpoint
        payload = {
            "accountId": self._account_id,
            "contractId": None,  # resolved below
        }

        # Resolve contract ID
        async with httpx.AsyncClient() as client:
            resp = await client.get(
                f"{self.base_url}/contract/find",
                params={"name": symbol},
                headers=self._headers(),
                timeout=10,
            )
            resp.raise_for_status()
            contract = resp.json()

        async with httpx.AsyncClient() as client:
            resp = await client.post(
                f"{self.base_url}/order/liquidateposition",
                json={"accountId": self._account_id, "contractId": contract["id"], "admin": False},
                headers=self._headers(),
                timeout=10,
            )
            resp.raise_for_status()
            return resp.json()

    async def get_positions(self) -> list:
        await self._ensure_authenticated()
        async with httpx.AsyncClient() as client:
            resp = await client.get(
                f"{self.base_url}/position/list",
                headers=self._headers(),
                timeout=10,
            )
            resp.raise_for_status()
            return resp.json()
