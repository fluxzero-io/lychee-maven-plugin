# Contributing

## Commit Convention

This project uses release automation. Use conventional-style commit messages so release notes and version bumps are accurate.

Examples:
- `feat: add support for custom headers`
- `fix: handle windows path normalization`
- `chore: update github actions dependencies`

## Pull Request Expectations

- CI must pass (`verify` + `site`).
- Keep PRs small and focused.
- Update docs/tests when behavior changes.

## Branch Protection Expectations

Configure branch protection on `main` with:
- Require pull request before merging.
- Require status checks to pass before merging: `CI`.
- Require branches to be up to date before merging.
- Include administrators.
- Allow auto-merge.

## Local Checks

Run locally before opening a PR:

```bash
./mvnw -B verify
./mvnw -B site
```
