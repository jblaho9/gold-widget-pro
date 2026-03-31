# Gold Widget Pro

Android home screen widgets showing live XAUUSD (gold) price and your open cTrader trade.

## Widgets

| Widget | Size | Shows |
|--------|------|-------|
| **Gold Price** | 2×2 | Price + % change from prev close |
| **Gold Price (Detailed)** | 4×2 | Price + day high/low/open/prev close |
| **Gold + Trade** | 4×3 | Price + day stats + open XAUUSD trade P&L |
| **Gold P&L** | 2×2 | Price + trade P&L — background fades red near SL, green near TP |

All widgets refresh every 15 minutes via WorkManager and also on phone unlock. Tap the refresh button for an immediate update.

## P&L accuracy

Trade P&L is calculated using the live bid price fetched directly from cTrader's own WebSocket feed (same session used to retrieve positions), so the value matches what cTrader shows. If no open position is found, the widget falls back to the Swissquote mid price.

## Setup

### 1. Register a cTrader app

1. Go to [connect.spotware.com](https://connect.spotware.com) and create a new application
2. Set the redirect URI to exactly: `http://localhost/callback`
3. Copy your **Client ID** and **Client Secret**

### 2. Add credentials to `local.properties`

```
CTRADER_CLIENT_ID=your_client_id
CTRADER_CLIENT_SECRET=your_client_secret
```

This file is gitignored — credentials are never committed.

### 3. Build

```bash
./gradlew assembleDebug
```

Open the app, tap **Connect cTrader**, log in, and select your trading account. The trade widgets will then show your open XAUUSD positions.

## P&L widget background logic

When a trade has both SL and TP set, the background color interpolates:

```
At SL ──── dark red ──── neutral dark ──── dark green ──── At TP
  0%                         50%                              100%
```

If SL/TP are not set on the position the background stays neutral.
