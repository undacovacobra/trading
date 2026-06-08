"""
FastAPI server that receives TradingView alerts and executes trades via browser automation.

TradingView alert message format (JSON):
{
  "secret": "your_webhook_secret",
  "action": "buy" | "sell" | "close",
  "symbol": "MNQ1!",
  "quantity": 1
}

Set your TradingView webhook URL to:  http://YOUR_SERVER_IP:8000/webhook
"""

import asyncio
import logging
import sys
from contextlib import asynccontextmanager

# Windows requires SelectorEventLoop for Playwright to work correctly
if sys.platform == "win32":
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel

import config
from tradovate_browser import TradovateBrowser

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
)
logger = logging.getLogger(__name__)

trader = TradovateBrowser()
_order_lock = asyncio.Lock()  # prevent simultaneous orders


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Starting Tradovate browser...")
    await trader.start()
    logger.info("Bot is ready.")
    yield
    logger.info("Shutting down...")
    await trader.stop()


app = FastAPI(lifespan=lifespan)


class AlertPayload(BaseModel):
    secret: str
    action: str        # "buy", "sell", or "close"
    symbol: str        # e.g. "MNQ1!"
    quantity: int = 1


@app.post("/webhook")
async def webhook(payload: AlertPayload):
    if payload.secret != config.WEBHOOK_SECRET:
        raise HTTPException(status_code=401, detail="Invalid secret")

    action = payload.action.lower()
    if action not in ("buy", "sell", "close"):
        raise HTTPException(status_code=400, detail=f"Unknown action: {action}")

    logger.info("Alert received: %s %s x%d", action.upper(), payload.symbol, payload.quantity)

    async with _order_lock:
        try:
            if action == "close":
                await trader.close_position(payload.symbol)
            else:
                await trader.place_order(payload.symbol, action, payload.quantity)
        except RuntimeError as e:
            # Trading hours / Lucid rule violation — reject but don't crash
            logger.warning("Order blocked: %s", e)
            raise HTTPException(status_code=403, detail=str(e))
        except Exception as e:
            logger.exception("Order failed")
            raise HTTPException(status_code=500, detail=str(e))

    return {"status": "ok", "action": action, "symbol": payload.symbol}


@app.get("/health")
async def health():
    return {"status": "running"}
