import hmac
import logging
import os
from contextlib import asynccontextmanager
from typing import Literal

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Request, status
from pydantic import BaseModel, Field

from browser_trader import TradovateBrowser

load_dotenv()

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-8s  %(message)s",
)
log = logging.getLogger(__name__)

WEBHOOK_SECRET = os.environ["WEBHOOK_SECRET"]

trader = TradovateBrowser(
    email=os.environ["TRADOVATE_EMAIL"],
    password=os.environ["TRADOVATE_PASSWORD"],
    headless=os.environ.get("HEADLESS", "true").lower() == "true",
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    log.info("Starting browser and logging in to Tradovate...")
    await trader.start()
    log.info("Ready to receive alerts.")
    yield
    log.info("Shutting down browser...")
    await trader.stop()


app = FastAPI(title="TradingView → Tradovate Webhook", lifespan=lifespan)


class AlertPayload(BaseModel):
    secret: str
    action: Literal["buy", "sell", "close"]
    symbol: str = Field(..., description="Futures symbol, e.g. MNQU4, ESU4, NQU4")
    quantity: int = Field(default=1, ge=1)


@app.post("/webhook", status_code=status.HTTP_200_OK)
async def webhook(payload: AlertPayload, request: Request):
    if not hmac.compare_digest(payload.secret, WEBHOOK_SECRET):
        log.warning("Rejected alert — bad secret from %s", request.client.host)
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid secret")

    log.info("Alert: %s %s x%d", payload.action.upper(), payload.symbol, payload.quantity)

    try:
        if payload.action in ("buy", "sell"):
            result = await trader.place_order(payload.symbol, payload.action, payload.quantity)
        elif payload.action == "close":
            result = await trader.close_position(payload.symbol)
        else:
            raise HTTPException(status_code=400, detail=f"Unknown action: {payload.action}")

        return {"status": "ok", "result": result}

    except Exception as exc:
        log.exception("Order execution failed")
        raise HTTPException(status_code=500, detail=str(exc))


@app.get("/health")
async def health():
    return {"status": "ok", "browser": "running"}
