#!/usr/bin/env bash
# Posts this release's notes (release/notes.json) on the updates issue, where the
# apps look for new versions and how urgent they are. Notes already announced (same
# "id") are not posted again: a build without new notes stays quiet.
#
# Env: GH_TOKEN (the workflow's token: comments then come from github-actions[bot],
# the only author the apps trust), UPDATE_ISSUE, GITHUB_REPOSITORY, GITHUB_RUN_NUMBER.
set -euo pipefail

notes=release/notes.json
issue=${UPDATE_ISSUE:?}
id=$(jq -r '.id // empty' "$notes")
priority=$(jq -r '.priority // empty' "$notes")
case "$priority" in
    optional | recommended | important | critical) ;;
    *) echo "::error title=release/notes.json::unknown priority '$priority'"; exit 1 ;;
esac
[ -n "$id" ] || { echo "::error title=release/notes.json::no id"; exit 1; }

# Ids of the releases announced so far (entries sit in an HTML comment on one line).
announced=$(gh api --paginate "repos/$GITHUB_REPOSITORY/issues/$issue/comments?per_page=100" \
    --jq '.[] | select(.user.login == "github-actions[bot]") | .body' \
    | grep -o 'tunnellock-update:{.*} -->' | sed -e 's/^tunnellock-update://' -e 's/ -->$//' \
    | jq -r 'select(.kind == "release") | .id' || true)
if grep -qxF -- "$id" <<<"$announced"; then
    echo "::notice title=Update feed::'$id' was announced before; nothing new to tell"
    exit 0
fi

version=$GITHUB_RUN_NUMBER
entry=$(jq -c --argjson v "$version" '{kind: "release", id, version: $v, name: ("0.1." + ($v | tostring)), priority, fa, en}' "$notes")
body=$(printf '### TunnelLock 0.1.%s: %s\n\n%s\n\n%s\n\n<!-- tunnellock-update:%s -->\n' \
    "$version" "$priority" "$(jq -r .fa "$notes")" "$(jq -r .en "$notes")" "$entry")
gh api "repos/$GITHUB_REPOSITORY/issues/$issue/comments" -f body="$body" >/dev/null
echo "::notice title=Update feed::announced 0.1.$version ($priority)"
