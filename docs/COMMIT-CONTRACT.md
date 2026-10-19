# Commit message contract

This project uses [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/)
with lowercase types/scopes and a maximum 72-character header.

```text
type(scope): imperative summary

Optional explanation of the problem and resulting behavior.

Validation: checks actually performed
```

Scope is optional. Use a short lowercase scope such as `core`, `auth`, `moodle`,
`daa`, `data`, `design`, `courses`, `schedule`, `inbox`, `academic`, `app` or `repo`.
Do not end the header with a period. Separate the body with a blank line. Write
messages in English; describe one coherent change per commit.

| Type | Use |
| --- | --- |
| `feat` | New application or library behavior |
| `fix` | Correct behavior that was wrong |
| `refactor` | Restructure code without changing behavior |
| `perf` | Improve performance |
| `test` | Add or improve tests |
| `docs` | Documentation changes |
| `build` | Build logic, SDK/tooling or dependencies |
| `ci` | Continuous integration configuration |
| `chore` | Repository maintenance |
| `style` | Formatting-only changes |
| `revert` | Revert an earlier change |

A new SSO feature is `feat(auth)`, rather than `chore`, because it adds behavior.
Use `!` immediately before the colon or a `BREAKING CHANGE:` footer for an
incompatible change. The body should explain the impact and migration when needed.

```text
feat(auth): add unified UIT SSO and encrypted session storage
fix(courses): retain cached materials after a failed refresh
chore(repo): ignore local Android build and signing files
refactor(data)!: replace the legacy snapshot storage contract
```

## Local enforcement

After cloning, run:

```sh
sh scripts/install-git-hooks.sh
```

Python 3 and Git are required. The versioned `commit-msg` hook checks the type,
scope syntax, header length, trailing period and blank-line separation. Choosing
the correct type, writing an imperative English summary, and assessing the change
still require review. The hook does not claim to infer semantics from prose.

The `pre-commit` hook checks the staged index for generated/local files, oversized
assets and common credential/private-key patterns. It prints paths, not secret
contents. This is a limited repository guard, not a comprehensive secret audit.

Both checks can also be run explicitly:

```sh
python3 scripts/check-repo-hygiene.py
python3 scripts/check-commit-message.py .git/COMMIT_EDITMSG
```

Hooks are configured per clone; versioning `.githooks` does not install them on
other machines. Validation statements in commit bodies must describe checks
actually run for that snapshot.
