Memory Profiling Test Utils Development Guide for AI Agents

## Reports

If the user asks for a report, make it in the tmp folder as a Markdown file.

## Work Log

- For non-trivial tasks, maintain a problem-specific Markdown work log (for example, ISSUE_142_WORKLOG.md) in `tmp/`.
- Record important findings, attempted approaches, failures, decisions, and next steps. Read it before starting or resuming work, and do not repeat failed approaches unless new evidence justifies them.
- Update the log after each major investigation step or milestone, so the current state can be recovered after interruption.
- Work-log/audit identifiers ("#45", "D10", "R7") live ONLY in `tmp/` logs and commit messages — never in code, KDoc, logs, or docs (`tmp/` is git-ignored, so such a reference points nowhere). Write the self-contained reason instead.
- Committable files must never reference `tmp/` paths (incl. screenshots), device-specific identifiers, or emulator names: say, "API-26 emulator" / "API-36 phone" instead.

## Asking for sources you cannot reliably reproduce

Do **not** fabricate or guess complex low-level sources (e.g., handwritten assembly, GPU shaders, or generated coefficient/lookup tables). If such a file is required, and you cannot reproduce it exactly, **ask the user to provide the file** — it is always acceptable to request it rather than invent a subtly wrong version.

## Commits

- Create commits without agent attribution footers (no "Created with ...", Co-authored-by, or similar trailers). Do not touch commit.gpgsign — PGP signing follows the repo config.
- **Never push without an explicit request.** Do not run `git push` (or equivalent) unless the user specifically asks you to push. Do not push after creating commits.

## Tooling preferences

- **Prefer IDE functions over shell commands** whenever one exists. Fall back to the terminal only when no IDE tool fits. Use the IDE's import optimization feature to remove unused imports, if possible.
- Always use the Gradle daemon (omit `--no-daemon`).
- Do not build after trivial modifications (e.g., one-line or comment-only changes): skip the build and let the user verify.

## Test Results & Caches

- When the user does not explicitly export IDE test run results, inspect Android Studio's internal test history cache directory:
  `~/Library/Caches/Google/AndroidStudio*/testHistory/<project-hash>/*.xml`
  to retrieve recent test run XML reports and failure stack traces.

## Writing style

- **`e.g.,` / `i.e.,` always carry a trailing comma**: American style, enforced by the Grazie grammar check — a bare `e.g.`/`i.e.` without comma (or with a single period, `eg.`) is flagged. Template placeholders like `[e.g. ...]` are exempt.
- **Recurring Grazie traps (4×+)**: one word — `antialiasing`, `handwritten`, `spotlight(s)`; American `afterward` (no `-s`).

## Temporary files
Always use the project's `tmp/` folder for temporary files (screenshots, dumps, scratch output). Never use the system `/tmp`.

## Searching files
Never search from the filesystem root (`/`) or outside the project (no `find /`, no unscoped sweeps). Always scope file/content searches to the project directory or an explicit subdirectory.

## Performance – avoid primitive boxing
- `takeIf` / `takeUnless` (and similar nullable-returning scope functions) are FORBIDDEN on primitive values (`Int`, `Long`, `Float`, `Double`, `Boolean`): the nullable result boxes the primitive (`Integer.valueOf` etc.) on every call. Use a plain `if` / `else`, which stays unboxed (verified on bytecode: plain `ifle` branches, zero `valueOf` calls).
  - Bad: `val w = d.intrinsicWidth.takeIf { it > 0 } ?: targetSize`
  - Good: `val raw = d.intrinsicWidth` + `val w = if (raw > 0) raw else targetSize`
- The same applies to any construct that needlessly wraps primitives in nullable/box types on frequently executed paths (draw, layout, bind, sync loops). Prefer primitive locals and specialized primitive collections over boxed generics.

- Use English internally and for all code, comments, documentation, logs, commit messages, and other project artifacts.
- You can answer in English even if the question is in Hungarian.
