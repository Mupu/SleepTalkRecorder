# Documentation responsibilities

All documentation is written in English. Each fact has one authoritative home;
other documents link to it rather than copying it. Ideas and target architecture
are not implementation authorization.

## Written for humans

Write documentation for a human reader: readable prose with sensible paragraphs and
spacing, and the reasoning behind a design wherever it is not obvious. The README
explains why the app exists and what it does, gives a short overview of its
architecture, and shows how to build and install it.

## Document roles

| Document | Owns | Does not contain |
|---|---|---|
| `AGENTS.md` | Working rules, each pointing to its detail | Explanations a linked document owns |
| `ARCHITECTURE.md` (once there is code) | Module boundaries, recording/storage/detection/playback design, accepted ADRs | Status, progress, plans |
| `GLOSSARY.md` | Domain vocabulary (night, chunk, voice line, baseline, ...) | Implementation detail |
| `docs/adr/` | Hard-to-reverse decisions and their rationale | Delivery history, ticket IDs |
| `docs/standards/` | Rules code is written and reviewed against | Workflow, tracker mechanics |
| `docs/agents/` | Tracker, Git, release and documentation mechanics | Product design |
| `CHANGELOG.md`, `CHANGELOG.<lang>.md` | Per release, what the user notices, in each app language; the app shows it under "What's new" | Internal history, file paths, ticket numbers, unannounced work |
| `README.md` | Purpose, architecture overview, development setup and full check, install on a phone, limitations | Status, test matrices |
| KDoc | API reference and why-comments | Ticket IDs, progress |
| GitHub issues | Planning, status, specs, research, decisions below the ADR bar | |

A change updates the documents it makes stale in the same branch.

## Keeping documents lean

Length guideline: `AGENTS.md` about 100 lines. Remove from documents: rules repeated from another document (link instead);
status, progress and counts; the history of how something came to be (it belongs
to the tracker and Git); catalogs that restate the environment, such as file lists
or command output.

## Decision records

Write an ADR only for a decision that is hard to reverse, surprising without
context, and the result of a real trade-off; any other decision is recorded in the
closing comment of its issue. An ADR has a title, `Status` and `Date` lines and
one to three paragraphs, including the rejected alternatives and why they lost.
Files are numbered `NNNN-title.md`. An ADR is `proposed` while discussed and
`accepted` once the owner agrees; a changed decision is a new ADR that supersedes
the old one.

## Planning and status

Planning lives in GitHub Issues, never in tracked files. Status is the issue
state; closing an issue is the status update. Close obsolete ideas rather than
keeping every discarded suggestion open.
