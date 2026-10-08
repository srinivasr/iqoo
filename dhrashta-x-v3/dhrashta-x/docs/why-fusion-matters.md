# Why Permission Fusion Matters

## The Core Problem

A banking trojan's network traffic, viewed in isolation, looks like a
normal HTTPS app:

- Connects periodically (every 30-120 seconds)
- Sends small encrypted payloads (1-10 KB)
- Talks to one destination (C2 server)
- Uses TLS (contents unreadable)

This is indistinguishable from:
- A weather widget checking for updates
- A news app polling headlines
- A messaging app fetching messages
- A fitness tracker syncing steps

Network signal alone is not enough.

## What Changes with Permission Context

Same network traffic, viewed alongside the phone's permission state:

