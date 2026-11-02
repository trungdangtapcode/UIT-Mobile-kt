# Isolated Discourse contract lab

The owner's real university account is read-only for research, except necessary
login already authorized by the owner. Posting, editing, reacting, voting, marking
notifications read and granting API keys must not be tested on that account.

This lab runs a separate upstream Discourse instance on localhost. Its generated
accounts, `.invalid` email addresses and posts are synthetic contract-test data.
They must never become published UIT Forum screenshot evidence. Passing this
harness establishes upstream HTTP behavior, not app mutation support or UIT parity.

## Pinned environment

Observed UIT upstream revision: `b81bee142d364526b432788251986871952b5ddb`.
The matching checkout specifies `discourse/discourse_dev:20260812-0036` in its
`.devcontainer/devcontainer.json`. Observed image digest:
`sha256:ed44e808f7430432712745da7245d6e256c0c4171d4c874772ca5b1b3d311242`.
UIT can include configuration and customizations absent from this upstream source.

The development setup works with rootless Docker over SSH; an Android device and
Android Studio are not required to research server contracts. App UI testing still
uses the separate host-rendering and device procedures in [Debugging](DEBUGGING.md).

Prerequisites: rootless Docker, Git, Python 3 with `requests`, sufficient disk for
the development image and Ruby/Node dependencies. The commands below create only
the task-specific source checkout and container. Do not pass university credentials,
`CPASSWORD`, API keys or a production database into the lab.

```sh
mkdir -p /tmp/uit-forum-test/discourse
git -C /tmp/uit-forum-test/discourse init
git -C /tmp/uit-forum-test/discourse remote add origin https://github.com/discourse/discourse.git
git -C /tmp/uit-forum-test/discourse fetch --depth 1 origin b81bee142d364526b432788251986871952b5ddb
git -C /tmp/uit-forum-test/discourse checkout --detach FETCH_HEAD

docker pull discourse/discourse_dev:20260812-0036
docker run -d --name uit-forum-lab --label uit-mobile.task=forum-parity \
  -p 127.0.0.1:39300:3000 -p 127.0.0.1:39292:9292 \
  -v /tmp/uit-forum-test/discourse:/src \
  -e UNICORN_BIND_ALL=true \
  discourse/discourse_dev:20260812-0036 /sbin/boot

docker exec -u root -w /src uit-forum-lab bundle install
docker exec -u root -w /src uit-forum-lab pnpm install --frozen-lockfile
# Local container DB only: use the role matching the rootless-mounted checkout.
docker exec -u postgres uit-forum-lab createuser --superuser root
docker exec -u root -w /src -e PGUSER=root -e RAILS_ENV=development \
  uit-forum-lab bundle exec rake db:create db:migrate
docker exec -d -u root -w /src -e PGUSER=root -e RAILS_ENV=development \
  -e UNICORN_WORKERS=1 -e UNICORN_SIDEKIQS=0 \
  uit-forum-lab bash -lc 'bin/dev > /tmp/uit-lab-server.log 2>&1'
```

Wait for `http://127.0.0.1:39300/site.json` to return JSON. Inspect startup with
`docker exec uit-forum-lab tail -30 /tmp/uit-lab-server.log`. Do not re-run creation
commands blindly against an existing checkout/container. The lab keeps email
workers disabled and the verification harness disables email delivery before
creating users. It uses cookies/CSRF, not a university token or admin API key.

## Local HTTP verification

From the app repository:

```sh
python3 scripts/verify-forum-lab.py
```

The harness refuses a container without the expected task label, source revision
or loopback-only port mapping. The HTTP origin is fixed in source; there is no
remote-URL argument. Redirects are rejected and environment proxies are ignored.
It creates fresh local users with random passwords held in process memory, checks
the marked local site, then exercises login, topic creation, edit history, reply
parent linkage and bookmark creation/removal. No raw cookies/passwords are printed
or written to the repository. Only aggregate evidence is exported to
`docs/research/evidence/uit-forum-local-contract.json` after every check passes.

The harness also snapshots local last-seen, topic reading and notification state
around authenticated XHR current-user/feed/topic/notification GETs. This checks a
specific read path, not every possible implicit server write. Session/token
rotation, other plugins and UIT customizations need separate investigation.

Upstream `lib/auth/default_current_user_provider.rb` at the pinned revision updates
last-seen for ordinary authenticated requests; XHR/API requests require
`Discourse-Present: true` for that branch. Therefore a future read-only native
transport must deliberately account for `X-Requested-With`, presence headers,
`track_visit`, timing endpoints and notification actions. GET alone does not prove
read-only account behavior. Do not experiment with these mutations on UIT.

## Windows laptop access and cleanup

For a browser on the SSH laptop, forward only the development port:

```sh
ssh -L 39300:127.0.0.1:39300 nguyentnt@YOUR_SERVER
```

Open `http://localhost:39300`. It is a separate local forum, not UIT and not the
Android app. Do not enter university credentials. Synthetic lab users have no
connection to university accounts. Contract tests do not export review screenshots.

Stop the task-specific container when not investigating contracts:

```sh
docker stop uit-forum-lab
```

No container/database removal is automatic. Starting the container later also
requires restarting the documented `bin/dev` process. Keep its data separate from
all production dumps and other development projects.
