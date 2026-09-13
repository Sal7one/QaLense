# Prompt for the next AI

Paste the following into an AI that has access to this repository:

---

You are taking over **QaLens**, an MIT-licensed Android Jetpack Compose QA evidence SDK.
The original checkout is `/Users/salehalanazi/Desktop/QaLense`; use the actual checkout location
if this repository has moved. You have no dependency on previous chat history.

Start by reading `AGENTS.md`, then **`HANDOVER.md` in full**. It is the authoritative project
handover: product, user priorities, code map, recent commits, tested environment, exact verification
commands, current behavior, limitations and safety contracts. Read `docs/ARCHITECTURE.md`,
`docs/CLIENT_SAFETY_FIXES.md` and `next.md` next. Use `docs/SAL_FORMAT.md` for archive work and
`integration.md` plus `docs/OSS_INTEGRATIONS.md` for host-app integrations.

The user wants a robust Android client first and an SDK that is easy to use with Chucker and other
open-source tools. Fourteen client-audit findings were addressed in `7a05bee`; the subsequent
handover cleanup changed docs and links only. Preserve those fixes and distinguish verified device
behavior from unresolved physical-device/lifecycle gaps. Do not revive obsolete claims from Git
history or treat old feature lists as a current backlog.

Inspect Git state and source before editing. Reproduce relevant verification, choose a concrete
client reliability task from `next.md`, implement and test it, then update the backlog/changelog
and commit a focused change. Preserve debug/no-op parity, host exception/stream behavior, capture
privacy, archive compatibility and honest evidence-loss reporting. Do not push or publish unless
the user asks. Report what actually passed and what remains unverified.

---
