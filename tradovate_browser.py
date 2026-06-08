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
            headless=False,  # visible so you can see what's happening
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
        await self._open_order_ticket(symbol)
        await self._set_quantity(quantity)
        await self._click_market_order(side)
        await self._confirm_if_prompted()
        logger.info("Order submitted: %s %s x%d", side.upper(), symbol, quantity)

    async def close_position(self, symbol: str):
        """Close any open position for the given symbol."""
        logger.info("Closing position for %s", symbol)
        await self._ensure_logged_in()
        await self._flatten_position(symbol)

    async def close_all_positions(self):
        """Close every open position — used for EOD rule enforcement."""
        logger.info("Closing all positions (EOD rule)")
        await self._ensure_logged_in()
        await self._flatten_all()

    # ------------------------------------------------------------------
    # Login
    # ------------------------------------------------------------------

    async def _login(self):
        await self._page.goto(TRADOVATE_URL, wait_until="networkidle")

        # If already on the trading platform, session is still valid
        if "/welcome" not in self._page.url and "tradovate.com" in self._page.url:
            logger.info("Session restored, already logged in")
            self._logged_in = True
            return

        logger.info("Logging in to Tradovate...")
        await self._page.wait_for_selector('input[name="name"]', timeout=15000)
        await self._page.fill('input[name="name"]', config.USERNAME)
        await self._page.fill('input[name="password"]', config.PASSWORD)
        await self._page.click('button[type="submit"]')

        # Wait for the trading UI to appear
        await self._page.wait_for_url("**/home**", timeout=30000)
        self._logged_in = True
        logger.info("Login successful")

        # Persist the session so next restart skips login
        await self._context.storage_state(path="session.json")

    async def _ensure_logged_in(self):
        if not self._logged_in:
            await self._login()
        # Quick sanity check — if we landed back on login page, re-authenticate
        if "/welcome" in self._page.url or "login" in self._page.url.lower():
            logger.warning("Session expired, re-logging in")
            self._logged_in = False
            await self._login()

    # ------------------------------------------------------------------
    # Order ticket interaction
    # ------------------------------------------------------------------

    async def _open_order_ticket(self, symbol: str):
        """Open the order ticket for the given symbol via the search bar."""
        page = self._page

        # Click the symbol search / add widget area
        await page.click('[data-testid="instrument-search"], .instrument-search, [placeholder*="Search"]')
        await page.wait_for_timeout(500)
        await page.keyboard.type(symbol, delay=80)
        await page.wait_for_timeout(800)

        # Select the first matching result
        result = page.locator('.search-result-item, [class*="searchResult"], [class*="SearchResult"]').first
        await result.wait_for(timeout=8000)
        await result.click()
        await page.wait_for_timeout(500)

        # Open order ticket if not already visible
        ticket = page.locator('[class*="orderTicket"], [class*="OrderTicket"], [data-testid="order-ticket"]')
        if not await ticket.is_visible():
            # Try right-clicking the chart to get "New Order" option
            chart = page.locator('[class*="chart-container"], canvas').first
            await chart.click(button="right")
            await page.wait_for_timeout(300)
            await page.get_by_text("New Order", exact=False).first.click()

    async def _set_quantity(self, quantity: int):
        page = self._page
        qty_input = page.locator(
            '[class*="qty"] input, [class*="quantity"] input, [data-testid="qty-input"], input[class*="Qty"]'
        ).first
        await qty_input.wait_for(timeout=5000)
        await qty_input.triple_click()
        await qty_input.type(str(quantity))

    async def _click_market_order(self, side: str):
        page = self._page
        if side == "buy":
            btn = page.locator(
                'button:has-text("Buy"), [class*="buyBtn"], [data-testid="buy-btn"], button[class*="Buy"]'
            ).first
        else:
            btn = page.locator(
                'button:has-text("Sell"), [class*="sellBtn"], [data-testid="sell-btn"], button[class*="Sell"]'
            ).first

        await btn.wait_for(timeout=5000)

        # Verify this is a market order (not limit)
        market_btn = page.locator('button:has-text("MKT"), button:has-text("Market"), [class*="market"]').first
        if await market_btn.is_visible():
            await market_btn.click()
            await page.wait_for_timeout(200)

        await btn.click()

    async def _confirm_if_prompted(self):
        """Dismiss any order confirmation dialog."""
        page = self._page
        await page.wait_for_timeout(800)
        confirm = page.locator('button:has-text("Confirm"), button:has-text("OK"), button:has-text("Submit")').first
        if await confirm.is_visible():
            await confirm.click()

    # ------------------------------------------------------------------
    # Position flattening
    # ------------------------------------------------------------------

    async def _flatten_position(self, symbol: str):
        page = self._page
        # Navigate to positions panel and close matching symbol
        pos_row = page.locator(f'[class*="position"]:has-text("{symbol}")').first
        if not await pos_row.is_visible():
            logger.info("No open position found for %s", symbol)
            return
        close_btn = pos_row.locator('button:has-text("Close"), button:has-text("Flatten")').first
        await close_btn.click()
        await self._confirm_if_prompted()

    async def _flatten_all(self):
        page = self._page
        flatten_btn = page.locator('button:has-text("Flatten All"), button:has-text("Close All")').first
        if await flatten_btn.is_visible():
            await flatten_btn.click()
            await self._confirm_if_prompted()
        else:
            logger.warning("Flatten All button not found")


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
