#!/usr/bin/env python3
"""Create isolated local credentials for production-client mutation tests.

No remote origin or host credentials are accepted. The output must be outside
this repository and is created exclusively with mode 0600.
"""
import importlib.util
import json
import os
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: seed-forum-member-lab.py /tmp/private-directory/credentials.json")
repo = Path(__file__).resolve().parents[1]
path = Path(sys.argv[1]).expanduser().resolve()
if path.is_relative_to(repo) or path.exists():
    raise SystemExit("Refusing a repository path or an existing credential file")
spec = importlib.util.spec_from_file_location("forum_lab", Path(__file__).with_name("verify-forum-lab.py"))
lab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lab)
revision = lab.validate_lab()
result=lab.rails('''
SiteSetting.title = "UIT Mobile isolated Forum lab"
SiteSetting.disable_emails = "yes"
SiteSetting.send_welcome_message = false
SiteSetting.enable_local_logins = true
SiteSetting.allow_uncategorized_topics = true
SiteSetting.editing_grace_period = 0
SiteSetting.chat_enabled = true
SiteSetting.chat_allowed_groups = "1|2|10|11|12"
SiteSetting.discourse_reactions_enabled = true
SiteSetting.discourse_reactions_enabled_reactions = "heart|laughing|clap"
SiteSetting.poll_enabled = true
suffix = SecureRandom.hex(4)
users = 2.times.map do |i|
  password = SecureRandom.hex(24)
  u = User.create!(username: "native#{i}_#{suffix}", email: "native#{i}_#{suffix}@example.invalid", password: password, active: true, approved: true, trust_level: 2)
  u.activate
  u.user_option.update!(chat_enabled: true)
  { id: u.id, username: u.username, password: password }
end
category = Category.find(SiteSetting.uncategorized_category_id)
channel = Chat::CategoryChannel.create!(name: "Native local #{suffix}", chatable: category, threading_enabled: true)
users.each { |u| Chat::UserChatChannelMembership.create!(chat_channel_id: channel.id, user_id: u[:id], following: true) }
puts "LAB_RESULT=" + { origin: "http://127.0.0.1:39300", users: users, category: category.id, channel: channel.id }.to_json
''')
result["upstream_revision"] = revision
path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "w") as output:
    json.dump(result, output)
print("Generated local-only identities; credentials are in the requested private file.")
