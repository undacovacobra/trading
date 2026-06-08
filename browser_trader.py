"""
Playwright-based automation for Tradovate web (trader.tradovate.com).

Keeps a single persistent browser session alive for the lifetime of the server.
All order placements go through this single session — no re-login per order.
"""

import asyncio
import logging
import os

from playwright.async_api import async_playwright, Browser, BrowserContext, Page, Playwright

log = logging.getLogger(__name__)

TRADOVATE_URL = "https://trader.tradovate.com"
LOGIN_URL = f"{TRADOVATE_URL}/#/auth/sign-in"


class TradovateBrowser:
    def __init__(self, email: str, password: str, headless: bool = True):
        self.email = email
        self.password = password
        self.headless = headless
        self._pw: Playwright | None = None
        self._browser: Browser | None = None
        self._context: BrowserContext | None = None
        self._page: Page | None = None
        self._lock = asyncio.Lock()

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    async def start(self) -> None:
        self._pw = await async_playwright().start()
        self._browser = await self._pw.chromium.launch(
            headless=self.headless,
            args=["--no-sandbox", "--disable-dev-shm-usage"],
        )
        self._context = await self._browser.new_context(
            # Persist login across server restarts by saving browser state
            storage_state="session.json" if os.path.exists("session.json") else None,
            viewport={"width": 1400, "height": 900},
        )
        self._page = await self._context.new_page()
        await self._ensure_logged_in()

    async def stop(self) -> None:
        if self._context:
            await self._context.storage_state(path="session.json")
        if self._browser:
            await self._browser.close()
        if self._pw:
            await self._pw.stop()

    # ------------------------------------------------------------------
    # Auth
    # ------------------------------------------------------------------

    async def _ensure_logged_in(self) -> None:
        await self._page.goto(TRADOVATE_URL, wait_until="networkidle", timeout=30_000)

        # If already on the trading interface, we're done
        if "/auth/sign-in" not in self._page.url and "sign-in" not in self._page.url:
            log.info("Already logged in (session restored)")
            return

        log.info("Logging in to Tradovate...")
        await self._page.goto(LOGIN_URL, wait_until="networkidle", timeout=30_000)

        # Fill login form
        # NOTE: If selectors break after a Tradovate UI update, open DevTools on
        # https://trader.tradovate.com/#/auth/sign-in and inspect the email/password inputs.
        await self._page.locator("input[name='email'], input[type='email']").first.fill(self.email)
        await self._page.locator("input[name='password'], input[type='password']").first.fill(self.password)
        await self._page.locator("button[type='submit'], button:has-text('Sign In')").first.click()

        # Wait for redirect to trading interface
        await self._page.wait_for_url(f"{TRADOVATE_URL}/**", timeout=30_000)
        await self._page.wait_for_load_state("networkidle", timeout=30_000)

        # Persist session so next server start skips login
        await self._context.storage_state(path="session.json")
        log.info("Login successful, session saved")

    # ------------------------------------------------------------------
    # Order placement
    # ------------------------------------------------------------------

    async def place_order(self, symbol: str, action: str, quantity: int) -> dict:
        """
        Place a market order via the Tradovate web UI.

        action: "buy" | "sell"
        """
        async with self._lock:  # serialize orders — never overlap UI interactions
            try:
                return await self._do_place_order(symbol, action.lower(), quantity)
            except Exception:
                # On any failure, take a screenshot for debugging and re-raise
                await self._page.screenshot(path=f"error_{symbol}_{action}.png")
                log.exception("Order failed — screenshot saved")
                raise

    async def _do_place_order(self, symbol: str, action: str, quantity: int) -> dict:
        log.info("Placing order: %s %s x%d", action.upper(), symbol, quantity)

        # ---- Step 1: Open the order ticket for the symbol ----
        # Tradovate lets you type a symbol in the search bar to open a chart/order ticket.
        # This uses the global search shortcut.
        await self._page.keyboard.press("Escape")  # close any open modal first

        # Click the symbol search input (top bar)
        search = self._page.locator(
            "input[placeholder*='Search'], input[placeholder*='symbol'], .symbol-search input"
        ).first
        await search.click()
        await search.fill(symbol)
        await self._page.keyboard.press("Enter")
        await self._page.wait_for_timeout(800)

        # ---- Step 2: Set quantity in the order entry panel ----
        qty_input = self._page.locator(
            "input[data-test='order-qty'], input[class*='quantity'], input[placeholder*='Qty']"
        ).first
        await qty_input.triple_click()  # select existing value
        await qty_input.fill(str(quantity))

        # ---- Step 3: Click the Buy or Sell button ----
        if action == "buy":
            btn = self._page.locator(
                "button[data-test='buy-btn'], button:has-text('Buy Market'), button.buy-market"
            ).first
        else:
            btn = self._page.locator(
                "button[data-test='sell-btn'], button:has-text('Sell Market'), button.sell-market"
            ).first

        await btn.click()
        await self._page.wait_for_timeout(500)

        # ---- Step 4: Confirm if a confirmation dialog appears ----
        confirm = self._page.locator("button:has-text('Confirm'), button:has-text('OK')").first
        if await confirm.is_visible(timeout=1_500):
            await confirm.click()
            await self._page.wait_for_timeout(300)

        log.info("Order submitted: %s %s x%d", action.upper(), symbol, quantity)
        return {"status": "submitted", "action": action, "symbol": symbol, "quantity": quantity}

    async def close_position(self, symbol: str) -> dict:
        """Flatten / close an open position using Tradovate's 'Close Position' button."""
        async with self._lock:
            log.info("Closing position for %s", symbol)

            # Right-click on the position row or use the positions panel
            # This targets the "Close" / "Flatten" button in the Positions tab
            position_row = self._page.locator(
                f"tr:has-text('{symbol}'), .position-row:has-text('{symbol}')"
            ).first
            await position_row.hover()

            close_btn = self._page.locator(
                "button:has-text('Close'), button:has-text('Flatten'), button[data-test='close-position']"
            ).first
            await close_btn.click()

            confirm = self._page.locator("button:has-text('Confirm'), button:has-text('OK')").first
            if await confirm.is_visible(timeout=1_500):
                await confirm.click()

            log.info("Position close submitted for %s", symbol)
            return {"status": "submitted", "action": "close", "symbol": symbol}
