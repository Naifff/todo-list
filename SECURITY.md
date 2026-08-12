# Security Policy

## Reporting a vulnerability

Use GitHub's **private vulnerability reporting** on the Security tab of this repository.

Please do not open a public issue for anything that lets one family read another family's
tasks, or that exposes a bot token. There is no bug bounty — this is a self-hosted hobby
project maintained by one person, and reports are handled on a best-effort basis.

## Supported versions

The tip of `main` only. There are no maintenance branches and no backported fixes.

## What has and has not been reviewed

**Nobody outside the project has audited it.** There is no cryptography here and no public
network surface — the bot opens no ports and speaks to Telegram over outbound long polling —
so the attack surface is small. Small is not the same as reviewed.

Every invariant below is covered by a test that fails if the invariant is broken. Run them:

```bash
./gradlew check
```

## Invariants

A change that breaks any of these is a blocker, not technical debt.

1. **Family isolation is a SQL filter, never an in-memory one.** Every repository method that
   returns tasks takes `familyId` as its first argument, and `family_id` is duplicated onto
   `task` for exactly that reason. Guarded by `CrossFamilyIsolationIT`.
2. **`callback_data` is untrusted input.** Buttons reflect permissions but do not enforce
   them: every handler reloads the entity and re-checks the actor. A forged id must fail.
3. **Logs carry identifiers only** — never task titles, decline reasons or member names.
   Guarded by `LogHygieneTest`, which checks both the message and the exception text, because
   user text leaks through causes more often than through templates.
4. **Secrets come from the environment only** (`BOT_TOKEN`, `BOT_USERNAME`, `DB_PATH`). The
   application refuses to start with an empty token instead of failing later as "network
   trouble".
5. **The exported HTML schedule contains no JavaScript and makes no external requests.** Task
   titles are user text, and that file is opened by a browser: every string goes through
   `HtmlEscaper`, and only numbers computed by us reach HTML attributes.
6. **No ports are opened.** Liveness is a log line, not an HTTP endpoint. Guarded by
   `NoWebServerTest`, which asserts the servlet API is absent from the classpath — a property
   that a one-line configuration change cannot undo.

## Known limitations

Design trade-offs, documented rather than hidden. They are not accepted as vulnerability
reports on their own, but a way to make one worse is.

- **One Telegram account belongs to exactly one family** (`telegram_user_id UNIQUE`). Separated
  parents and a grown-up child with their own family need a model migration, not a column.
- **A parent sees the whole family's tasks**, including the other parent's. This is deliberate:
  the calendar exists so that people can see who is busy when. Children see only their own.
- **Telegram sees everything.** Messages travel through Telegram's servers in plaintext, as
  with any bot. Do not put anything in a task that you would not put in a Telegram chat.
- **The operator sees the database.** It is a SQLite file on the host you run this on.
- **Invite codes are 128-bit and single-use with a 24-hour lifetime**, but anyone holding a
  live link joins the family. Treat the link like a password.
