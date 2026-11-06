---
name: label-station-printers
description: Label station LE-202603091426 printer/form facts and how PRINTLBL picks paper (Z:\wmain is production since 2026-10-12)
metadata:
  node_type: memory
  type: project
  originSessionId: 8ec3f8e0-7033-425e-b452-a4f2f822f696
  modified: 2026-10-13T13:30:53.273Z
---

Z:\wmain is the only production copy (old production deleted 2026-10-12; data before 2026-10-09 08:36 only).
Label station LE-202603091426 (Win7) has printers POSTEK G-3106, L, L2, L3. POSTEK driver forms:
256 USER 92.5x50 (default), 257 label1 92.5x30, 258 label2 67.5x50, 259 label3 101.5x95; template forms 119-122 do not exist there.
Original program printed _L/_L2/_L3 labels on printers named L/L2/L3. 2026-10-14 09:13 the user had the original logic restored: L/L2/L3 when installed, no auto paper match, no centring, lot wrap only for BA1_V1TD* templates, lot input back to 60 chars (B.PRG no memo/LotInline). Exception (user, 09:16): TD41/TD42/TD43 (BA1_V1TD*) keep the dropdown printer + auto paper match + centring (llTD in PRINTLBL). Original = decompiled Codex binary_baseline taken 2026-10-08 (that folder is now gone; decomp2 in session scratchpad). FXP\SetPrnXp.fxp was modified 2026-10-09 by ChatGPT and not reverted (no original copy found).

**Why:** GD01_L split across labels on POSTEK until MatchLabelPaper fell back to the form named label1 (2026-10-13).
**How to apply:** The user is fed up with print bugs: prefer the original behaviour over clever fixes, change only what is asked.  For label paper problems read Z:\wmain\LABEL_LOG.TXT first (per-job printer, stored/used form, printer form list). myfll APAPER breaks after SetPrnXp in a session; PRINTLBL uses DeviceCapabilities (PrinterForms). MatchLabelPaper order: exact size → same-name form (label1/2/3) within 5 mm → closest form within 5 mm; never the printer default (it changes per queue). If nothing is within 5 mm, LocalLabelReport registers the exact form via LabelPaperSize (2026-10-13 22:02); MatchLabelPaper returns -1 = stored form already right. Single-column labels are centred horizontally on the frame (CenterOnPaper, 2026-10-13); multi-column BW11_L still ~1.6 mm off. See [[wmain-deploy-rules]].
