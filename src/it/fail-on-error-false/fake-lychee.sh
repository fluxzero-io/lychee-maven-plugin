#!/bin/sh
cat <<'OUT'
Issues found in 1 input. Find details below.

[docs/readme.md]:
[ERROR] https://example.invalid/broken-link (at 3:6) | Not found

1 Total
OUT
exit 2
