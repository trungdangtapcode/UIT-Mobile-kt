#!/usr/bin/env python3
"""Exercise Discourse writes on the explicitly labelled localhost lab only.

Never accepts a remote URL, university credentials or an API key. All output is
aggregate contract evidence, not Forum screenshot data or an app acceptance claim.
Requires Python requests and the documented rootless Docker lab.
"""
import json
from pathlib import Path
import subprocess
import time
from datetime import datetime, timezone

import requests

CONTAINER = "uit-forum-lab"
ORIGIN = "http://127.0.0.1:39300"
MARKER = "UIT Mobile isolated Forum lab"
REVISION = "b81bee142d364526b432788251986871952b5ddb"


def docker(*args, stdin=None):
    result = subprocess.run(["docker", *args], input=stdin, capture_output=True, text=True, check=False)
    if result.returncode:
        raise RuntimeError("Local Docker operation failed; inspect the lab privately")
    return result.stdout


def rails(code):
    output = docker("exec", "-i", "-u", "root", "-w", "/src", "-e", "PGUSER=root", "-e", "RAILS_ENV=development", CONTAINER, "bundle", "exec", "rails", "runner", "-", stdin=code)
    lines = [line.removeprefix("LAB_RESULT=") for line in output.splitlines() if line.startswith("LAB_RESULT=")]
    if len(lines) != 1:
        raise RuntimeError("Local Rails contract returned no result")
    return json.loads(lines[0])


def validate_lab():
    inspect = json.loads(docker("inspect", CONTAINER))[0]
    if inspect["Config"].get("Labels", {}).get("uit-mobile.task") != "forum-parity":
        raise RuntimeError("Refusing an unlabelled container")
    port = inspect["NetworkSettings"]["Ports"].get("3000/tcp")
    if port != [{"HostIp": "127.0.0.1", "HostPort": "39300"}]:
        raise RuntimeError("Lab port must be bound exclusively to the documented loopback address")
    actual = docker("exec", "-w", "/src", CONTAINER, "git", "rev-parse", "HEAD").strip()
    if actual != REVISION:
        raise RuntimeError("Lab source does not match the documented research revision")

    return actual


def main():
    actual = validate_lab()

    # Only generated .invalid identities enter the isolated database. Email jobs
    # are disabled before creating either user. No host credential env is passed.
    seed = rails('''
SiteSetting.disable_emails = "yes"
SiteSetting.send_welcome_message = false
SiteSetting.title = "UIT Mobile isolated Forum lab"
SiteSetting.enable_local_logins = true
SiteSetting.allow_uncategorized_topics = true
SiteSetting.editing_grace_period = 0
suffix = SecureRandom.hex(4)
users = 2.times.map do |i|
  password = SecureRandom.hex(24)
  u = User.create!(username: "lab#{i}_#{suffix}", email: "lab#{i}_#{suffix}@example.invalid", password: password, active: true, approved: true, trust_level: 2)
  u.activate
  { id: u.id, username: u.username, password: password }
end
puts "LAB_RESULT=" + { users: users, category: SiteSetting.uncategorized_category_id }.to_json
''')
    clients = []
    evidence = []
    try:
        for user in seed["users"]:
            client = requests.Session()
            client.trust_env = False
            client.headers.update({"Accept": "application/json", "X-Requested-With": "XMLHttpRequest"})
            clients.append(client)

            def read(path, c=client):
                return call(c, "GET", path)

            site = read("/about.json").get("about", {})
            if site.get("title") != MARKER:
                raise RuntimeError("Refusing an unmarked Forum server")
            client.headers["X-CSRF-Token"] = read("/session/csrf.json")["csrf"]
            login = call(client, "POST", "/session", data={"login": user["username"], "password": user["password"]})
            if login.get("error"):
                raise RuntimeError(f"Local login rejected: reason {login.get('reason')}, error {login.get('error')}")
            current = read("/session/current.json")
            if login.get("error") or current.get("current_user", {}).get("id") != user["id"]:
                raise RuntimeError(f"Local login failed: response keys {sorted(login)}, current keys {sorted(current)}, reason {login.get('reason')}, cookie names {[c.name for c in client.cookies]}")
            client.headers["X-CSRF-Token"] = read("/session/csrf.json")["csrf"]
        evidence.append("Generated local users authenticate with session cookies and CSRF")
        author, reader = clients
        post = call(author, "POST", "/posts.json", data={"title": "Isolated contract exercise " + str(int(time.time())), "raw": "Local contract body with **bold text** and a code example. This is not UIT content.", "category": seed["category"], "archetype": "regular"})
        post_id, topic_id = post["id"], post["topic_id"]
        call(author, "PUT", f"/posts/{post_id}.json", data={"post[raw]": "Revised local contract body with **bold text** and a code example. This is not UIT content.", "post[edit_reason]": "Local revision contract"})
        topic = call(reader, "GET", f"/t/{topic_id}.json")
        edited = next(p for p in topic["post_stream"]["posts"] if p["id"] == post_id)
        if edited["version"] < 2 or not edited["can_view_edit_history"]:
            raise RuntimeError("Local edit did not produce visible revision history")
        history = call(reader, "GET", f'/posts/{post_id}/revisions/{edited["version"]}.json')
        if history["post_id"] != post_id or not history.get("body_changes", {}).get("inline"):
            raise RuntimeError("Local history payload is incomplete")
        evidence.append("Create and edit yield permitted cooked content and an inline revision diff")
        reply = call(reader, "POST", "/posts.json", data={"topic_id": topic_id, "raw": "Local reply contract only. No university account or content is involved.", "reply_to_post_number": 1})
        if reply["reply_to_post_number"] != 1:
            raise RuntimeError("Reply-parent contract was not preserved")
        bookmark = call(reader, "POST", "/bookmarks.json", data={"bookmarkable_type": "Post", "bookmarkable_id": post_id, "name": "Local contract bookmark"})
        if not bookmark.get("id"):
            raise RuntimeError("Bookmark contract did not return an identity")
        call(reader, "DELETE", f'/bookmarks/{bookmark["id"]}.json')
        evidence.append("Reply targets and bookmark create/delete work in the isolated lab")
        user_id = seed["users"][1]["id"]

        def snapshot():
            return rails(f'''
u = User.find({user_id})
puts "LAB_RESULT=" + {{ last_seen: u.last_seen_at, read: TopicUser.where(user_id: u.id).order(:topic_id).pluck(:topic_id, :last_read_post_number, :last_visited_at, :total_msecs_viewed, :notification_level), notifications: Notification.where(user_id: u.id).order(:id).pluck(:id, :read) }}.to_json
''')

        before = snapshot()
        for path in ["/session/current.json", "/latest.json", f"/t/{topic_id}.json", "/notifications.json"]:
            call(reader, "GET", path)
        time.sleep(2)
        after = snapshot()
        if before != after:
            raise RuntimeError("Authenticated XHR reads changed tracked account state in the lab")
        evidence.append("XHR current-user/feed/topic/notification GETs without Discourse-Present or tracking parameters preserve sampled last-seen, read-position and notification state")
        result = {"captured_at_utc": datetime.now(timezone.utc).isoformat(), "environment": "Isolated localhost rootless Docker Discourse; generated accounts and content", "upstream_revision": actual, "production_UIT_requests": 0, "university_credentials_used": False, "app_mutation_acceptance": False, "checks": evidence, "limits": "Upstream contracts only. UIT customization, session rotation, other implicit writes and installed Android authentication remain unverified."}
        target = Path(__file__).resolve().parents[1] / "docs/research/evidence/uit-forum-local-contract.json"
        target.write_text(json.dumps(result, indent=2) + "\n")
        print(f"Passed {len(evidence)} isolated contract groups; aggregate evidence: {target.name}")
    finally:
        for client in clients:
            client.cookies.clear()
            client.close()


def call(client, method, path, **kwargs):
    if not path.startswith("/") or path.startswith("//"):
        raise RuntimeError("Invalid local path")
    response = client.request(method, ORIGIN + path, timeout=30, allow_redirects=False, **kwargs)
    if response.status_code not in (200, 201, 204):
        details = response.json().get("errors", []) if "application/json" in response.headers.get("Content-Type", "") else []
        raise RuntimeError(f"Local {method} contract failed with HTTP {response.status_code}: {details}")
    if response.status_code == 204 or not response.content:
        return {}
    if "application/json" not in response.headers.get("Content-Type", ""):
        raise RuntimeError("Local contract did not return JSON")
    return response.json()


if __name__ == "__main__":
    main()
