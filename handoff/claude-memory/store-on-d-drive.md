---
name: store-on-d-drive
description: "User wants everything Claude creates on their Windows PC stored on the D: drive, not C:"
metadata:
  node_type: memory
  type: feedback
  originSessionId: c0e7ecf3-296d-4923-93a4-ef0a95131d8a
  modified: 2026-11-03T16:17:57.330Z
---

Put all new files, tools, downloads, build outputs, emulator images and caches Claude creates on the user's Windows PC under D: (e.g. D:\Android for the emulator/AVDs, D:\orea-chromium for the Chromium build scripts and APK/AAR outputs).

**Why:** the user said 「所有需要我Windows內存的東西全部放D槽」; C: is their system drive and they don't want it filling up.

**How to apply:** default new locations to D:\; existing projects already on C (e.g. C:\Users\barry\Developer\andromeda, Gradle cache) stay unless the user asks to move them.
