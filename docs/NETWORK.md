# Vystorm network (proxy hub) — experimental

> **Experimental:** Velocity and BungeeCord/Waterfall support is new and has not been tested with many real networks yet. Please report problems; behaviour may still change.

Since Core 0.53.0 the same `Vystorm_Core` jar runs on Paper/Spigot servers and on a Velocity or BungeeCord/Waterfall proxy. On the proxy it is only the network hub.

## Setup

1. Put the Core jar into `plugins/` of the proxy **and** of every server. Restart.
2. **Velocity with `player-info-forwarding-mode = "modern"`:** nothing else to do. The key comes from the forwarding secret on both sides (servers need `proxies.velocity.enabled: true` in `config/paper-global.yml`, which modern forwarding requires anyway).
3. **BungeeCord/Waterfall, or Velocity without modern forwarding:** join each server once. The proxy offers pairing; the proxy console and the server console show the same code. On the server run `vnet pair accept <code>` (console or permission `vystorm_core.network.admin`). Offers expire after 10 minutes.
4. Check with `/vnet status` and `/vnet servers`.

Servers learn their name and the hub address from the proxy the first time a player joins them and remember it (`plugins/vystorm_core/network/hub.properties`). From then on they also connect while empty and after restarts.

## Settings

Proxy: `plugins/vystorm_core/network.properties`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | hub on/off |
| `bind-host` | `127.0.0.1` | listen address; use the LAN address or `0.0.0.0` for servers on other machines |
| `port` | `25599` | hub port |
| `advertise-host` | (bind-host) | address the servers are told to use |
| `allow` | (empty) | comma-separated IPs/CIDRs allowed to connect |
| `hub-id` | `proxy` | name shown in pairing requests |
| `key` | (empty) | optional fixed Base64 key (32 bytes) for all servers |

Server: `config.yml` → `network:` — `enabled` (`auto`/`false`), `server-name`, `hub.host`, `hub.port`, `key` (only needed if the automatic values do not fit), and `servers.<name>.display|icon|hidden` for the server selector.

Proxy console: `vnet` (status, servers, pending pairing codes), `vnet unpair <server>`.

## Security model

- The key never travels: Velocity secret → HKDF; pairing → X25519 + HKDF with a confirmation code the admin compares.
- Every connection proves knowledge of the key in both directions (HMAC challenge–response) before anything else is accepted.
- Traffic is encrypted and authenticated with AES-256-GCM, one key per direction; implicit sequence numbers reject replayed, reordered or modified records.
- The hub listens on localhost by default; an allow list and a ban (10 minutes after 5 failed logins within a minute) protect an exposed port; frames are capped at 1 MiB.
- The proxy drops anything a client sends on `vystorm:net`. A forged identity at worst makes a server try a hub that cannot pass the key proof. A server only accepts a pairing offer while it has no key, and only after the admin confirms the code.
- Keys are stored owner-readable only and never logged (logs show an 8-character fingerprint).

## Limits

- Folia is not supported.
- Plugin messages are not queued for offline servers.
- The server selector needs Core to be connected on the target server (others are shown as "not reachable").
