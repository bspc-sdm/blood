#!/usr/bin/env bash
# CI-র লগ একটা আলাদা branch-এ রাখা (Actions-এর লগ নামানো sandbox থেকে বন্ধ, তাই সেখান থেকে git দিয়ে পড়া হয়)।
#   publish-log.sh <log-branch> <src-dir> <owned-path>...
# <src-dir>-এর সব ফাইল branch-এর গোড়ায় বসে; <owned-path>গুলো (এই workflow-এর নিজের ফাইল/ফোল্ডার) আগে মোছা হয়।
# অন্য workflow-এর ফাইল থেকে যায় (backend.yml: status.txt …, backend-e2e.yml: e2e-status.txt, e2e/ …)।
# branch-এ সবসময় একটাই commit (ইতিহাস জমে না); একসাথে দুটো workflow লিখলে --force-with-lease আটকায়, তখন আবার চেষ্টা।
# দরকার: GH_TOKEN (contents: write), GITHUB_REPOSITORY, LOG_MESSAGE (ঐচ্ছিক)।
set -euo pipefail
branch="$1"; src="$2"; shift 2
remote="https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
for attempt in 1 2 3 4 5 6; do
  work="$(mktemp -d)"
  git -C "$work" init -q
  git -C "$work" config user.name ci
  git -C "$work" config user.email ci@users.noreply.github.com
  base=""
  if git -C "$work" fetch -q --depth 1 "$remote" "refs/heads/$branch" 2>/dev/null; then
    base="$(git -C "$work" rev-parse FETCH_HEAD)"
    git -C "$work" checkout -q FETCH_HEAD
  fi
  for p in "$@"; do rm -rf "${work:?}/$p"; done
  cp -R "$src"/. "$work"/
  git -C "$work" add -A
  tree="$(git -C "$work" write-tree)"
  commit="$(git -C "$work" commit-tree "$tree" -m "${LOG_MESSAGE:-CI log ${GITHUB_SHA:-}}")"
  if git -C "$work" push -q --force-with-lease="refs/heads/$branch:$base" "$remote" "$commit:refs/heads/$branch"; then
    echo "log → $branch"
    exit 0
  fi
  echo "push to $branch raced (attempt $attempt), retrying"
  sleep $((attempt * 3))
done
echo "::warning::could not publish the log to $branch"
