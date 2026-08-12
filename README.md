# Family todo

A shared todo list for one family, driven entirely from Telegram. Anyone in the family can ask
someone else to do something, mark it done, or decline with a reason. Reminders arrive before
the deadline, and a short digest arrives every morning.

You host it yourself: one jar file and a database file next to it. No web server, no ports.

The interface and the source comments are in Russian. Russian documentation:
[README.ru.md](README.ru.md).

---

## Status

Working software, deployed and used daily by the family it was written for. It has never been
reviewed by anyone outside the project.

This is a hobby project with an opinion, not a product. It solves one household's problem:
spoken requests like "take the bins out" get lost, and a group chat turns into a feed where
nothing can be found. A request here has an addressee, a state and a deadline.

## What it does

- **Private chats, one bot for many families.** Families are isolated; you join by a one-time
  invitation link.
- **Three states for a task:** open, done, declined. Declining is a real answer to a request,
  not an error — it carries a reason.
- **Two roles.** A parent sees the whole family's list; a child sees only their own — but can
  ask anyone, including a parent.
- **A task can be given to several people at once.** A doctor's appointment for a child is
  needed by both parents; entered twice it would produce two reminders, two calendar blocks and
  "done" for one while it still hangs for the other.
- **Names and colours.** A parent edits how a person is labelled; their tasks are drawn in
  their colour, which is how you tell whose is whose in a week grid at a glance.
- **A schedule for 1, 3, 7 or 30 days** — as a message, or as a self-contained HTML file, as a
  grid or as a list. History for the past week or month, in the same two forms.
- **Recurring tasks:** daily, weekdays, or chosen days of the week — for several people too.
- **Two shopping lists**, groceries and household, shared by the whole family. Anyone fills
  them in, including children.
- **Reminders and two morning digests:** today, and the week ahead. The digest is personal —
  only what was asked of you.

## What it does not do

- **Telegram sees everything.** Messages travel through Telegram's servers like any bot's. Do
  not put anything in a task you would not put in a chat.
- **No web interface.** There is nothing to open in a browser. The exported schedule is a file
  you download, not a page that is served.
- **No multi-family accounts.** One Telegram account belongs to exactly one family.
- **A parent sees the other parent's tasks.** Deliberate: a shared calendar exists so that
  people can see who is busy when. Children see only their own.

## Design decisions worth knowing

Every non-obvious choice is explained where it lives — in the javadoc of the class that makes
it, next to the code it constrains, rather than in a document that drifts away from both. Most
are marked ⚠️ and were paid for by a bug found on a real phone rather than by a test.

A few:

- **Family isolation is structural, not disciplined.** `family_id` is duplicated onto `task`
  precisely so that it can be a mandatory first argument of every query method.
- **"Done" closes a task for everyone; "can't" removes only the person who pressed it.** Done
  is a fact about the world; declining is an answer to a request, and every addressee answers
  for themselves. The task is declined only when everyone has declined.
- **Times are stored as epoch millis, never as text.** `Instant.toString()` drops zero
  fractions, and `...T16:00:00.123Z` sorts *before* `...T16:00:00Z`.
- **The exported schedule contains no JavaScript and makes no external requests.** A link is a
  blank space in a file downloaded to be read without a network — and a task title is user text
  that a browser would execute.
- **No health endpoint**, because no port is opened. Liveness is a line in the log, and the
  guarantee is the absence of the servlet API from the classpath, not a configuration flag.

## Commands

| Command | What it does |
|---------|--------------|
| `/start` | Join a family by invitation |
| `/new` | Ask for something: title, one or more assignees, deadline, repetition |
| `/agenda` | Schedule for 1, 3, 7 or 30 days; history for the past week or month |
| `/shop` | Shopping lists: groceries and household |
| `/my` | What was asked of me |
| `/assigned` | What I asked of others |
| `/series` | Recurring rules: stop one, or set the date it should run until |
| `/all` | The whole family's tasks (parents only) |
| `/family` | Members, invitations, names and colours, settings |
| `/help` | This list |

## Built with

Java 25, Spring Boot 4.1, SQLite. No web server: the bot uses long polling, that is, outbound
connections only, and listens on no port.

```
domain/         entities and rules. No Spring, no JPA, not a single annotation
application/    use cases and ports — the interfaces through which the domain asks for the world
adapter/telegram/     router, handlers, keyboards, message and file rendering
adapter/persistence/  SQL and port implementations
adapter/scheduler/    reminders, morning digest, materialising recurring tasks
config/         bean wiring
```

Layer boundaries are enforced by ArchUnit tests, not by convention. The domain must be testable
without starting Spring: if checking a permission rule needs a context, the rule has leaked out
of its layer.

## Build and test

Java 25 is the only prerequisite. Gradle arrives through the wrapper; there is no database to
install, because the database is a file.

```bash
./gradlew check
```

That runs the unit tests and the integration tests against a real SQLite file. Separately:

```bash
./gradlew test
```

```bash
./gradlew integrationTest
```

Always through `./gradlew` — the build version must not depend on what happens to be installed.

## Running it

Get a token from [@BotFather](https://t.me/BotFather), then:

```bash
cp .env.example .env
```

Fill it in and build:

```bash
./gradlew bootJar
```

Start it so that the variables come from the file rather than the command line — process
arguments are visible in `ps` to every user on the machine:

```bash
set -a && source .env && set +a && java -jar build/libs/family-todo-0.0.1-SNAPSHOT.jar
```

The sign of life is `long polling started` in the log. There is no health endpoint, on purpose.

An empty `BOT_TOKEN` or `BOT_USERNAME` stops startup deliberately: otherwise the bot comes up,
Telegram answers 404, and the log makes it look like a network problem.

### Environment

| Variable | Meaning |
|----------|---------|
| `BOT_TOKEN` | Token from BotFather. If it leaks, revoke it through BotFather — deleting the file is not enough |
| `BOT_USERNAME` | Bot name without `@`; used to build invitation links |
| `DB_PATH` | Database file. `./data/family-todo.db` locally |
| `FAMILY_CREATION_ENABLED` | Whether new families may register. `false` by default — invitation only |

### New families are closed by default

`/start` without an invitation code answers that a link is needed; it does not create a family.
The default lives in the code, not only in `application.yml`, so that a forgotten setting
cannot quietly open registration to a stranger who found the bot through search. The switch
closes family creation but **not** joining by invitation — otherwise it would also lock out the
people who were already sent a link.

## Deploying

Systemd, with the database as a file next to the service. Step by step:
[deploy/install.md](deploy/install.md).

```bash
./deploy/deploy.sh user@host
```

The script builds locally and ships only the jar. Backups are taken with `sqlite3 .backup`
rather than by copying the file: with WAL enabled some recent pages live in a separate file,
and a plain copy may fail to open — silently, until you need it.

⚠️ If the machine is shared with anything else, remember you are a guest there. Anything
system-wide — firewall rules, `sysctl`, journald quotas, installing or restarting Docker —
affects your neighbours, and the cost of a mistake is theirs. `install.md` marks such steps.

## Contributing

This is a personal project shaped around one family's habits, and it is opinionated on purpose.
Issues and pull requests are welcome, but a change that drops a decision marked ⚠️ in the code
will be asked to argue with the reasoning written next to it first — those notes exist because
most of them were paid for with a bug.

Before sending a change:

```bash
./gradlew check
```

## License

[GNU Affero General Public License v3.0](LICENSE).

AGPL rather than a permissive license on purpose. The bot holds a family's private
correspondence about their days and lives on a server for exactly as long as it is trusted. A
license that allowed running a modified, closed version for other people would hollow that out.
