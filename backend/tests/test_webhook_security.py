from tests.conftest import SECRET, Harness


def _body(**kw):
    b = {"secret": SECRET, "event_id": "sec-1", "strategy_id": "reversal_v6", "symbol": "NQ1!", "action": "ENTER_LONG"}
    b.update(kw)
    return b


def test_bad_secret_rejected_and_logged(h):
    r = h.client.post("/api/webhooks/tradingview", json=_body(secret="nope"))
    assert r.status_code == 401
    assert h.get("/api/signals") == []
    events = h.get("/api/system-events")
    assert any(e["code"] == "WEBHOOK_BAD_SECRET" for e in events)
    assert all("nope" not in e["message"] for e in events)


def test_malformed_json(h):
    r = h.client.post("/api/webhooks/tradingview", content=b"{not json", headers={"content-type": "application/json"})
    assert r.status_code == 400 and r.json()["code"] == "MALFORMED_JSON"


def test_unsupported_action(h):
    r = h.client.post("/api/webhooks/tradingview", json=_body(action="BUY_EVERYTHING"))
    assert r.status_code == 422 and r.json()["code"] == "UNSUPPORTED_SIGNAL"


def test_missing_event_id(h):
    b = _body()
    del b["event_id"]
    r = h.client.post("/api/webhooks/tradingview", json=b)
    assert r.status_code == 422 and r.json()["code"] == "MALFORMED_SIGNAL"


def test_body_size_limit(h):
    r = h.client.post("/api/webhooks/tradingview", json=_body(metadata={"x": "a" * 20_000}))
    assert r.status_code == 413


def test_secret_not_persisted(h):
    h.client.post("/api/webhooks/tradingview", json=_body())
    h.drain()
    ev = h.get("/api/signals")[0]
    assert "secret" not in ev["payload"]


def test_lowercase_action_and_string_numbers_accepted(h):
    r = h.client.post("/api/webhooks/tradingview", json=_body(action="enter_long", entry_price="24500.25"))
    assert r.status_code == 200
    h.drain()
    assert h.positions()[0]["futures_entry"] == 24500.25


def test_rate_limit(db_url, clock, market):
    with Harness(db_url, clock, market, webhook_rate_limit_per_minute=3) as h:
        codes = [h.client.post("/api/webhooks/tradingview", json=_body(secret="x")).status_code for _ in range(5)]
        assert codes[:3] == [401, 401, 401] and codes[3:] == [429, 429]


def test_https_and_ip_allowlist(db_url, clock, market):
    with Harness(db_url, clock, market, require_https=True, tradingview_ip_allowlist=True, trust_proxy_headers=True) as h:
        assert h.client.post("/api/webhooks/tradingview", json=_body()).status_code == 403
        r = h.client.post(
            "/api/webhooks/tradingview",
            json=_body(),
            headers={"x-forwarded-proto": "https", "x-forwarded-for": "1.2.3.4"},
        )
        assert r.status_code == 403 and r.json()["code"] == "IP_NOT_ALLOWED"
        r = h.client.post(
            "/api/webhooks/tradingview",
            json=_body(),
            headers={"x-forwarded-proto": "https", "x-forwarded-for": "52.89.214.238"},
        )
        assert r.status_code == 200


def test_dashboard_requires_auth(h):
    assert h.client.get("/api/status").status_code == 401
    assert h.client.get("/api/status", auth=("admin", "wrong")).status_code == 401
    assert h.client.get("/api/status", auth=("admin", "pw")).status_code == 200
