#!/bin/sh
if [ -z "$UID" ] || [ -z "$GID" ]; then
    echo "You have to set \$UID and \$GID through -e UID=\$(id -u) -e GID=\$(id -g)"
    exit 1
fi
adduser -s /bin/sh -u $UID -G $(getent group "$GID" | cut -d: -f1) -h /workspace -D app >/dev/null 2>&1
sudo -EHu app /usr/bin/env PATH="$PATH" "$@"