import hmac
import time
from collections import defaultdict, deque

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPBasic, HTTPBasicCredentials

# Published TradingView webhook source addresses (optional allowlist).
TRADINGVIEW_IPS = frozenset({"52.89.214.238", "34.212.75.30", "54.218.53.128", "52.32.178.7"})

_basic = HTTPBasic(auto_error=False)


def client_ip(request: Request, trust_proxy: bool) -> str:
    if trust_proxy:
        fwd = request.headers.get("x-forwarded-for")
        if fwd:
            return fwd.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


def is_https(request: Request, trust_proxy: bool) -> bool:
    if request.url.scheme == "https":
        return True
    return trust_proxy and request.headers.get("x-forwarded-proto", "").lower() == "https"


class RateLimiter:
    """Sliding one-minute window per client IP."""

    def __init__(self, per_minute: int):
        self.per_minute = per_minute
        self._hits: dict[str, deque] = defaultdict(deque)

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        q = self._hits[key]
        while q and now - q[0] > 60:
            q.popleft()
        if len(q) >= self.per_minute:
            return False
        q.append(now)
        return True


class BodyTooLarge(Exception):
    pass


async def read_limited_body(request: Request, limit: int) -> bytes:
    declared = request.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > limit:
        raise BodyTooLarge()
    chunks, size = [], 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > limit:
            raise BodyTooLarge()
        chunks.append(chunk)
    return b"".join(chunks)


def require_dashboard_auth(request: Request, creds: HTTPBasicCredentials | None = Depends(_basic)) -> None:
    env = request.app.state.env
    if env.dashboard_auth_disabled:
        return
    if not env.dashboard_password:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Set DASHBOARD_PASSWORD to use the dashboard")
    ok = creds is not None and hmac.compare_digest(creds.username.encode(), env.dashboard_user.encode()) and hmac.compare_digest(
        creds.password.encode(), env.dashboard_password.encode()
    )
    if not ok:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Unauthorized", headers={"WWW-Authenticate": "Basic"})
