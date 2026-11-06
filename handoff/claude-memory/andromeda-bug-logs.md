---
name: andromeda-bug-logs
description: Where the Andromeda/Orea browser writes automatic anomaly logs on the phone and how to read them
metadata:
  node_type: memory
  type: reference
  originSessionId: c0e7ecf3-296d-4923-93a4-ef0a95131d8a
  modified: 2026-10-30T00:47:02.606Z
---

The browser (C:\Users\barry\Developer\andromeda, package com.liquid.browser.next) auto-logs anomalies to
/sdcard/Android/data/com.liquid.browser.next/files/bugs on the phone: events.log (crash, anr, freeze, jank, stuck_*, js, http, slow, memory, load, renderer, translate, download) plus auto_NN_kind_time/ and report_NN_time/ folders (screen.jpg, info.txt, events.txt, logcat.txt).

Run `scripts/buglogs.ps1` in the repo to pull and summarise them. The user asked for this so Claude reads these logs first when they report a problem.

**How to apply:** when the user reports a bug or "卡住/衝突/卡頓", pull the logs before guessing.
