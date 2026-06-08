import asyncio
import logging
from datetime import datetime, time
from zoneinfo import ZoneInfo
from playwright.async_api import async_playwright, Page, Browser, BrowserContext

import config

logger = logging.getLogger(__name__)

TRADOVATE_URL = "https://trader.tradovate.com"
EST = ZoneInfo("America/New_York")
MARKET_CLOSE_CUTOFF = time(16, 45)  # Lucid rule: close all positions by 4:45 PM EST


class TradovateBrowser:
    def __init__(self):
        self._playwright = None
        self._browser: Browser | None = None
        self._context: BrowserContext | None = None
        self._page: Page | None = None
        self._logged_in = False

    async def start(self):
        self._playwright = await async_playwright().start()
        self._browser = await self._playwright.chromium.launch(
            headless=False,
            args=["--no-sandbox"],
        )
        self._context = await self._browser.new_context(
            viewport={"width": 1280, "height": 900},
            storage_state="session.json" if _session_file_exists() else None,
        )
        self._page = await self._context.new_page()
        await self._login()

    async def stop(self):
        if self._context:
            await self._context.storage_state(path="session.json")
        if self._browser:
            await self._browser.close()
        if self._playwright:
            await self._playwright.stop()

    # ------------------------------------------------------------------
    # Public trading methods
    # ------------------------------------------------------------------

    async def place_order(self, symbol: str, side: str, quantity: int = 1):
        """Place a market order. side must be 'buy' or 'sell'."""
        _check_trading_hours()
        side = side.lower()
        if side not in ("buy", "sell"):
            raise ValueError(f"Invalid side: {side}")

        logger.info("Placing %s %s x%d", side.upper(), symbol, quantity)
        await self._ensure_logged_in()
        await self._set_quantity(quantity)
        await self._click_market_order(side)
        await self._confirm_if_prompted()
        logger.info("Order submitted: %s %s x%d", side.upper(), symbol, quantity)

    async def close_position(self, symbol: str):
        """Close open position using the Exit at Mkt & Cxl button."""
        logger.info("Closing position for %s", symbol)
        await self._ensure_logged_in()
        await self._click_exit()

    async def close_all_positions(self):
        """Close every open position — used for EOD rule enforcement."""
        logger.info("Closing all positions (EOD rule)")
        await self._ensure_logged_in()
        await self._click_exit()

    # ------------------------------------------------------------------
    # Login
    # ------------------------------------------------------------------

    async def _login(self):
        await self._page.goto(TRADOVATE_URL, wait_until="domcontentloaded", timeout=60000)

        url = self._page.url
        blocked_pages = ("/welcome", "trading-mode", "/login", "auth")
        if "tradovate.com" in url and not any(p in url for p in blocked_pages):
            logger.info("Session restored, already logged in")
            self._logged_in = True
            return

        logger.info("Logging in to Tradovate...")
        await self._page.wait_for_selector('input[name="name"]', timeout=15000)
        await self._page.fill('input[name="name"]', config.USERNAME)
        await self._page.fill('input[name="password"]', config.PASSWORD)
        await self._page.click('button[type="submit"]')

        # Handle the "trading-mode" / prop terms page if it appears
        await self._page.wait_for_timeout(3000)
        if "trading-mode" in self._page.url:
            logger.info("Handling trading-mode/terms page...")
            await self._page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
            await self._page.wait_for_timeout(1500)
            clicked = await self._page.evaluate("""
                (() => {
                    const keywords = ['accept', 'agree', 'continue', 'start', 'get started', 'enter', 'proceed', 'understood', 'ok'];
                    const btns = Array.from(document.querySelectorAll('button, a[role="button"]'));
                    for (const btn of btns) {
                        const text = (btn.innerText || btn.textContent || '').toLowerCase().trim();
                        if (keywords.some(k => text.includes(k))) {
                            btn.scrollIntoView();
                            btn.click();
                            return text;
                        }
                    }
                    return null;
                })()
            """)
            if clicked:
                logger.info("Clicked button: '%s'", clicked)
            else:
                logger.warning("No matching button found on trading-mode page")

        await self._page.wait_for_url("**trader.tradovate.com/**", timeout=30000)
        self._logged_in = True
        logger.info("Login successful")
        await self._context.storage_state(path="session.json")

    async def _ensure_logged_in(self):
        if not self._logged_in:
            await self._login()
        url = self._page.url
        if "/welcome" in url or "login" in url.lower() or "trading-mode" in url:
            logger.warning("Session lost, re-logging in")
            self._logged_in = False
            await self._login()

    # ------------------------------------------------------------------
    # Order execution — directly clicks Buy Mkt / Sell Mkt on the DOM
    # ------------------------------------------------------------------

    async def _set_quantity(self, quantity: int):
        """Set the quantity in the order size input visible on the chart header."""
        page = self._page
        # The quantity box is the number input near the Buy Mkt / Sell Mkt buttons
        qty = page.locator('input[class*="qty"], input[class*="Qty"], input[class*="quantity"], input[class*="Quantity"], input[class*="size"], input[class*="Size"]').first
        if not await qty.is_visible():
            # Fallback: find any small number input near the buy button
            qty = page.locator('input[type="number"]').first
        await qty.triple_click()
        await qty.fill(str(quantity))
        await page.wait_for_timeout(200)

    async def _click_market_order(self, side: str):
        """Click Buy Mkt or Sell Mkt button."""
        page = self._page
        if side == "buy":
            btn = page.get_by_role("button", name="Buy Mkt")
        else:
            btn = page.get_by_role("button", name="Sell Mkt")
        await btn.wait_for(timeout=10000)
        await btn.click()
        logger.info("Clicked %s Mkt button", side.capitalize())

    async def _click_exit(self):
        """Click Exit at Mkt & Cxl to close the open position."""
        page = self._page
        btn = page.get_by_role("button", name="Exit at Mkt & Cxl")
        if await btn.is_visible():
            await btn.click()
            await self._confirm_if_prompted()
            logger.info("Clicked Exit at Mkt & Cxl")
        else:
            logger.info("No open position to close (Exit button not active)")

    async def _confirm_if_prompted(self):
        """Dismiss any order confirmation dialog."""
        page = self._page
        await page.wait_for_timeout(600)
        confirm = page.locator('button:has-text("Confirm"), button:has-text("OK"), button:has-text("Submit")').first
        if await confirm.is_visible():
            await confirm.click()


# ------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------

def _check_trading_hours():
    now = datetime.now(EST).time()
    if now >= MARKET_CLOSE_CUTOFF:
        raise RuntimeError(
            f"Trading blocked after {MARKET_CLOSE_CUTOFF} EST (Lucid rule). Current time: {now}"
        )


def _session_file_exists() -> bool:
    import os
    return os.path.exists("session.json")
