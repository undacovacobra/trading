"""
One-shot setup script. Run this ONCE on your local machine:

    python setup_and_run.py

It will:
  1. Create a .env file (prompting for your credentials)
  2. Log in to Tradovate, inspect the real UI elements
  3. Patch browser_trader.py with the verified selectors
  4. Start the webhook server + print ngrok instructions
"""

import asyncio
import json
import os
import re
import subprocess
import sys


# ---------------------------------------------------------------------------
# Step 1 – collect credentials
# ---------------------------------------------------------------------------

def create_env():
    if os.path.exists(".env"):
        print(".env already exists — skipping credential prompt.\n")
        return

    print("=== Tradovate credentials ===")
    email = input("Tradovate email: ").strip()
    password = input("Tradovate password: ").strip()
    secret = input("Choose a webhook secret (any random string): ").strip()

    with open(".env", "w") as f:
        f.write(f"TRADOVATE_EMAIL={email}\n")
        f.write(f"TRADOVATE_PASSWORD={password}\n")
        f.write(f"WEBHOOK_SECRET={secret}\n")
        f.write("HEADLESS=false\n")

    print(".env created.\n")


# ---------------------------------------------------------------------------
# Step 2 – inspect live Tradovate UI and extract selectors
# ---------------------------------------------------------------------------

async def inspect_tradovate(email: str, password: str) -> dict:
    from playwright.async_api import async_playwright

    print("Opening Tradovate in browser (HEADLESS=false so you can watch)...")

    async with async_playwright() as pw:
        browser = await pw.chromium.launch(headless=False, slow_mo=400)
        context = await browser.new_context(viewport={"width": 1400, "height": 900})
        page = await context.new_page()

        # ---- Login page ----
        await page.goto("https://trader.tradovate.com", wait_until="domcontentloaded", timeout=30_000)
        await page.wait_for_timeout(2000)

        print("Inspecting login form...")
        selectors = {}

        # Find email input
        for sel in [
            "input[name='email']",
            "input[type='email']",
            "input[placeholder*='Email' i]",
            "input[placeholder*='Username' i]",
        ]:
            el = page.locator(sel).first
            if await el.count() > 0:
                selectors["email_input"] = sel
                print(f"  email input: {sel}")
                break

        # Find password input
        for sel in [
            "input[name='password']",
            "input[type='password']",
        ]:
            el = page.locator(sel).first
            if await el.count() > 0:
                selectors["password_input"] = sel
                print(f"  password input: {sel}")
                break

        # Find submit button
        for sel in [
            "button[type='submit']",
            "button:has-text('Sign In')",
            "button:has-text('Log In')",
            "button:has-text('Login')",
        ]:
            el = page.locator(sel).first
            if await el.count() > 0:
                selectors["submit_button"] = sel
                print(f"  submit button: {sel}")
                break

        # ---- Log in ----
        print("\nLogging in...")
        await page.locator(selectors["email_input"]).fill(email)
        await page.locator(selectors["password_input"]).fill(password)
        await page.locator(selectors["submit_button"]).click()

        try:
            await page.wait_for_url("https://trader.tradovate.com/**", timeout=30_000)
        except Exception:
            pass
        await page.wait_for_load_state("networkidle", timeout=30_000)
        await page.wait_for_timeout(3000)

        print("Logged in. Inspecting trading interface...")

        # ---- Quantity input ----
        qty_candidates = [
            "input[data-test='qty']",
            "input[data-test='order-qty']",
            "input[class*='qty' i]",
            "input[class*='quantity' i]",
            "input[placeholder*='Qty' i]",
            "input[placeholder*='Quantity' i]",
            ".order-entry input[type='number']",
            ".order-entry input[type='text']",
        ]
        for sel in qty_candidates:
            el = page.locator(sel).first
            if await el.count() > 0:
                selectors["qty_input"] = sel
                print(f"  qty input: {sel}")
                break

        # ---- Buy / Sell buttons ----
        buy_candidates = [
            "button[data-test='buy-market']",
            "button[data-test='buy']",
            "button.buy-market",
            "button:has-text('Buy Market')",
            "button:has-text('BUY')",
            "[class*='buy'][class*='market']",
        ]
        for sel in buy_candidates:
            el = page.locator(sel).first
            if await el.count() > 0:
                selectors["buy_button"] = sel
                print(f"  buy button: {sel}")
                break

        sell_candidates = [
            "button[data-test='sell-market']",
            "button[data-test='sell']",
            "button.sell-market",
            "button:has-text('Sell Market')",
            "button:has-text('SELL')",
            "[class*='sell'][class*='market']",
        ]
        for sel in sell_candidates:
            el = page.locator(sel).first
            if await el.count() > 0:
                selectors["sell_button"] = sel
                print(f"  sell button: {sel}")
                break

        # ---- Confirm dialog (optional) ----
        for sel in ["button:has-text('Confirm')", "button:has-text('OK')"]:
            el = page.locator(sel).first
            if await el.count() > 0:
                selectors["confirm_button"] = sel
                print(f"  confirm button: {sel}")
                break

        # ---- Symbol search ----
        search_candidates = [
            "input[placeholder*='Search' i]",
            "input[placeholder*='Symbol' i]",
            ".symbol-search input",
            "[class*='search'] input",
            "[data-test='symbol-search']",
        ]
        for sel in search_candidates:
            el = page.locator(sel).first
            if await el.count() > 0:
                selectors["symbol_search"] = sel
                print(f"  symbol search: {sel}")
                break

        # Save session so server doesn't need to re-login
        await context.storage_state(path="session.json")
        await browser.close()

    print(f"\nSelectors found: {json.dumps(selectors, indent=2)}")
    return selectors


# ---------------------------------------------------------------------------
# Step 3 – patch browser_trader.py with the real selectors
# ---------------------------------------------------------------------------

def patch_browser_trader(selectors: dict):
    path = "browser_trader.py"
    with open(path) as f:
        src = f.read()

    replacements = {
        # Email input
        "email_input": (
            r'input\[name=\'email\'\],\s*input\[type=\'email\'\]',
            selectors.get("email_input", "input[type='email']"),
        ),
        # Password input
        "password_input": (
            r'input\[name=\'password\'\],\s*input\[type=\'password\'\]',
            selectors.get("password_input", "input[type='password']"),
        ),
        # Submit button
        "submit_button": (
            r'button\[type=\'submit\'\],\s*button:has-text\(\'Sign In\'\)',
            selectors.get("submit_button", "button[type='submit']"),
        ),
        # Qty input
        "qty_input": (
            r'input\[data-test=\'order-qty\'\],\s*input\[class\*=\'quantity\'\],\s*input\[placeholder\*=\'Qty\'\]',
            selectors.get("qty_input", "input[placeholder*='Qty']"),
        ),
        # Buy button
        "buy_button": (
            r'button\[data-test=\'buy-btn\'\],\s*button:has-text\(\'Buy Market\'\),\s*button\.buy-market',
            selectors.get("buy_button", "button:has-text('Buy Market')"),
        ),
        # Sell button
        "sell_button": (
            r'button\[data-test=\'sell-btn\'\],\s*button:has-text\(\'Sell Market\'\),\s*button\.sell-market',
            selectors.get("sell_button", "button:has-text('Sell Market')"),
        ),
        # Symbol search
        "symbol_search": (
            r'input\[placeholder\*=\'Search\'\],\s*input\[placeholder\*=\'symbol\'\],\s*\.symbol-search input',
            selectors.get("symbol_search", "input[placeholder*='Search']"),
        ),
    }

    patched = src
    for key, (pattern, replacement) in replacements.items():
        new_src = re.sub(pattern, replacement.replace("\\", "\\\\"), patched)
        if new_src != patched:
            print(f"  Patched {key}")
        patched = new_src

    with open(path, "w") as f:
        f.write(patched)

    print("browser_trader.py updated with live selectors.\n")


# ---------------------------------------------------------------------------
# Entrypoint
# ---------------------------------------------------------------------------

def load_env_var(key):
    with open(".env") as f:
        for line in f:
            line = line.strip()
            if line.startswith(key + "="):
                return line.split("=", 1)[1]
    return None


def main():
    print("=" * 60)
    print("  TradingView → Tradovate Webhook — One-Time Setup")
    print("=" * 60 + "\n")

    # 1. Credentials
    create_env()

    email = load_env_var("TRADOVATE_EMAIL")
    password = load_env_var("TRADOVATE_PASSWORD")
    if not email or not password:
        print("ERROR: Could not read credentials from .env")
        sys.exit(1)

    # 2. Inspect live UI
    selectors = asyncio.run(inspect_tradovate(email, password))

    # 3. Patch selectors
    print("\nPatching browser_trader.py...")
    patch_browser_trader(selectors)

    # 4. Switch to headless now that setup is done
    with open(".env") as f:
        env = f.read()
    env = env.replace("HEADLESS=false", "HEADLESS=true")
    with open(".env", "w") as f:
        f.write(env)

    print("Setup complete!\n")
    print("Next steps:")
    print("  1. In a separate terminal, run:  ngrok http 8000")
    print("  2. Copy the ngrok https URL")
    print("  3. Start the server:             uvicorn main:app --host 0.0.0.0 --port 8000")
    print("  4. In TradingView, set webhook URL to:  https://<ngrok-url>/webhook")
    print("  5. Set the alert message body to:")
    print('     {"secret":"<your_webhook_secret>","action":"buy","symbol":"MNQU4","quantity":1}')
    print()
    print("Test with:  curl -X POST http://localhost:8000/webhook \\")
    print('  -H "Content-Type: application/json" \\')
    secret = load_env_var("WEBHOOK_SECRET") or "YOUR_SECRET"
    print(f'  -d \'{{"secret":"{secret}","action":"buy","symbol":"MNQU4","quantity":1}}\'')


if __name__ == "__main__":
    main()
