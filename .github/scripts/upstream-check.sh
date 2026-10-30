#!/usr/bin/env bash
# Looks for newer versions of what TunnelLock is built from (the Xray core and
# OpenVPN 2.6) and, when the newest TunnelLock is behind, posts that on the updates
# issue once: the apps then say that a new TunnelLock build is needed.
#
# Env: GH_TOKEN, UPDATE_ISSUE, GITHUB_REPOSITORY.
set -euo pipefail
issue=${UPDATE_ISSUE:?}

app_version=$(gh release view --repo "$GITHUB_REPOSITORY" --json name --jq .name | grep -oE '[0-9]+$')
current_xray=$(grep -oP '^\s*XRAY_LIB_VERSION:\s*\K\S+' .github/workflows/build.yml)
latest_xray=$(gh api repos/2dust/AndroidLibXrayLite/releases/latest --jq .tag_name)
current_ovpn=$(grep -oP '^OPENVPN_TAG=\K\S+' native/build-openvpn.sh)
latest_ovpn=$(gh api "repos/OpenVPN/openvpn/tags?per_page=100" --jq '.[].name' | grep -E '^v2\.6\.[0-9]+$' | sort -V | tail -1)

announced=$(gh api --paginate "repos/$GITHUB_REPOSITORY/issues/$issue/comments?per_page=100" \
    --jq '.[] | select(.user.login == "github-actions[bot]") | .body' \
    | grep -o 'tunnellock-update:{.*} -->' | sed -e 's/^tunnellock-update://' -e 's/ -->$//' \
    | jq -r 'select(.kind == "upstream") | "\(.component)|\(.latest)|\(.appVersion)"' || true)

announce() { # <component> <current> <latest>
    local component=$1 current=$2 latest=$3 entry
    if [ -z "$latest" ] || [ "$current" = "$latest" ]; then
        echo "$component: $current is the newest"
        return
    fi
    if grep -qxF -- "$component|$latest|$app_version" <<<"$announced"; then
        echo "$component: $latest was announced for 0.1.$app_version before"
        return
    fi
    entry=$(jq -cn --arg c "$component" --arg cur "$current" --arg l "$latest" --argjson v "$app_version" \
        '{kind: "upstream", component: $c, current: $cur, latest: $l, appVersion: $v}')
    gh api "repos/$GITHUB_REPOSITORY/issues/$issue/comments" \
        -f body="$(printf '### %s %s is out\n\nTunnelLock 0.1.%s still has %s: a new build is needed.\n\n<!-- tunnellock-update:%s -->\n' \
            "$component" "$latest" "$app_version" "$current" "$entry")" >/dev/null
    echo "::notice title=Upstream::$component $latest announced (TunnelLock 0.1.$app_version has $current)"
}

announce "Xray core" "$current_xray" "$latest_xray"
announce "OpenVPN" "$current_ovpn" "$latest_ovpn"
