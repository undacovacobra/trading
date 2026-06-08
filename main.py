import logging
import os
from contextlib import asynccontextmanager
from typing import Literal

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Request, status
from pydantic import BaseModel, Field

from tradovate import TradovateClient

load_dotenv()

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-8s  %(message)s",
)
log = logging.getLogger(__name__)

WEBHOOK_SECRET = os.environ["WEBHOOK_SECRET"]

tradovate = TradovateClient(
    username=os.environ["TRADOVATE_USERNAME"],
    password=os.environ["TRADOVATE_PASSWORD"],
    app_id=os.environ.get("TRADOVATE_APP_ID", "Sample App"),
    app_version=os.environ.get("TRADOVATE_APP_VERSION", "1.0"),
    cid=os.environ["TRADOVATE_CID"],
    secret=os.environ["TRADOVATE_SECRET"],
    demo=os.environ.get("TRADOVATE_DEMO", "true").lower() == "true",
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    log.info("Authenticating with Tradovate...")
    await tradovate._authenticate()
    log.info("Tradovate authenticated. Account: %s (id=%s)", tradovate._account_spec, tradovate._account_id)
    yield


app = FastAPI(title="TradingView → Tradovate Webhook", lifespan=lifespan)


class AlertPayload(BaseModel):
    secret: str
    action: Literal["buy", "sell", "close"]
    symbol: str = Field(..., description="Tradovate contract name, e.g. ESU4, NQU4, MNQU4")
    quantity: int = Field(default=1, ge=1)


@app.post("/webhook", status_code=status.HTTP_200_OK)
async def webhook(payload: AlertPayload, request: Request):
    # Verify secret — constant-time compare to resist timing attacks
    import hmac
    if not hmac.compare_digest(payload.secret, WEBHOOK_SECRET):
        log.warning("Rejected webhook — bad secret from %s", request.client.host)
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid secret")

    log.info("Alert received: action=%s symbol=%s qty=%d", payload.action, payload.symbol, payload.quantity)

    try:
        if payload.action == "buy":
            result = await tradovate.place_market_order(payload.symbol, "Buy", payload.quantity)
        elif payload.action == "sell":
            result = await tradovate.place_market_order(payload.symbol, "Sell", payload.quantity)
        elif payload.action == "close":
            result = await tradovate.close_position(payload.symbol, payload.quantity)
        else:
            raise HTTPException(status_code=400, detail=f"Unknown action: {payload.action}")

        log.info("Order result: %s", result)
        return {"status": "ok", "result": result}

    except Exception as exc:
        log.exception("Order failed: %s", exc)
        raise HTTPException(status_code=500, detail=str(exc))


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "account": tradovate._account_spec,
        "account_id": tradovate._account_id,
        "mode": "demo" if tradovate.base_url.startswith("https://demo") else "live",
    }


@app.get("/positions")
async def positions():
    return await tradovate.get_positions()
