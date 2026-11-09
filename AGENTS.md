# Working Agreements

SleepTalkRecorder is a private Android app (Kotlin, Jetpack Compose) that records the whole night and
plays back only the parts where something was said, each mapped to the wall-clock time it happened.

## Owner authority

- Build only what the owner selects. Agree scope, data model and visible acceptance with the owner before implementation; within an approved ticket, refactor freely where it serves the ticket.
- Discuss substantial changes to governing documentation (architecture, ADRs, standards, this file) with the owner first; make small mechanical corrections and report them.
- Before choosing a custom implementation, research external libraries using primary sources and present fit, license, maintenance and trade-offs. A new dependency needs owner approval; approved dependencies can be reused freely.
- Decide from current Android and industry standards; surface conflicts between a request and governing documentation instead of silently overriding either.
- Keep all work inside this checkout unless the owner permits otherwise.

## Working

- Every piece of work (change, fix, investigation or research) has an issue before it starts, assigned with `gh issue edit <n> --add-assignee @me` as the first write. It stays assigned and open until its result comment is posted. A session that delegates work assigns the issue itself before it starts the subagent and names the number in the prompt. Helpers inside an already assigned issue need no issue of their own.
- Where the tool supports session names, name the session after its issue right after assigning it: `#<issue> <issue title>`, or `#<map> #<ticket> <ticket title>` for a ticket of a wayfinder map.
- Develop small, visible vertical slices; prototypes shorten feedback but do not complete building blocks.
- Hold everything permanent to the [code standards](docs/standards/code.md) and follow the [Git policy](docs/agents/git.md): one squashed Conventional Commit per issue on `main` (`type(scope): description (#N)`, no pull requests), Semantic Versioning from `CHANGELOG.md` from 1.0.0 on, linear history.
- A change is done when [the full check](README.md#development) passes, the documents it makes stale are updated in the same commit, a releasing change has its `CHANGELOG.md` section, and its issue is closed with a short result comment that names the commit. Close finished issues yourself; a gap becomes a new issue.
- Prefer verifying visible behaviour yourself (run the app on the emulator or the owner's phone and drive it) over asking the owner to test, and back such claims with screenshots or recordings attached to the issue. Behaviour only a real night on a real phone can show (Doze, OEM battery killers, an 8-hour recording) is named as unverified in the issue.
- Record research results in full as a comment on their ticket, never as tracked files.
- Commits carry only the human author: no co-author trailers, generated-by notes or session links.
- A larger change gets a `/code-review` of its diff before it is pushed.
- Recordings stay on the device or under the ignored `.local/`: never commit audio, keystores or `local.properties`.

## Proportion

- A change is shown to work by the full check and the tests written for it; anything more an acceptance needs is stated in its ticket.
- While working, run the smallest check that could fail for the change and never repeat a passing one. Run the full check once before pushing.
- Tests cover behaviour that can break, not documentation text or file layout.
- Tooling that only checks or reports is scope like a feature and needs owner approval.
- When checking a change will take longer than making it, ask the owner first.
- Reviews look for substantiated blockers rather than nits and skip what passing checks enforce; findings are fixed or rejected with a reason, never logged in documents.

## Writing

- Write everything in this repository and its tracker in English: source, documentation, comments, UI texts, commit messages and issues. Date ADRs and changelog sections as `YYYY-MM-DD` in local time.
- Write comments and documentation for a reader without access to tickets or conversations; ticket IDs, progress and review history belong in the issue tracker.
- Follow [documentation responsibilities](docs/agents/documentation.md) for which document owns what.

## Agent skills

### Issue tracker

Issues, specs and wayfinder maps live in GitHub Issues. See [`docs/agents/issue-tracker.md`](docs/agents/issue-tracker.md).

### Triage labels

The five default triage labels. See [`docs/agents/triage-labels.md`](docs/agents/triage-labels.md).

### Domain docs

Single-context: `GLOSSARY.md` and `docs/adr/`. See [`docs/agents/domain.md`](docs/agents/domain.md).

Skills from `mattpocock/skills` are installed unmodified into `.agents/skills` and `.claude/skills` and listed in `skills-lock.json` (`npx skills@latest update`; add one with `npx skills@latest add mattpocock/skills -s <name> -a codex claude-code --copy -y`); project rules belong here, never in skill bodies. Own skills are kept as identical copies in both folders. A new skill needs owner approval, like a dependency.
