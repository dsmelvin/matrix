#!/bin/sh
if [ -z "$UID" ] || [ -z "$GID" ]; then
    echo "You have to set \$UID and \$GID through -e UID=\$(id -u) -e GID=\$(id -g)"
    exit 1
fi
if command -v useradd >/dev/null 2>&1; then
    # Debian based image (native-image variant)
    GROUP_NAME=$(getent group "$GID" | cut -d: -f1)
    if [ -z "$GROUP_NAME" ]; then
        groupadd -g "$GID" app >/dev/null 2>&1
        GROUP_NAME=app
    fi
    useradd -s /bin/sh -u "$UID" -g "$GROUP_NAME" -d /workspace -M app >/dev/null 2>&1
else
    # Alpine based image (jar variant)
    adduser -s /bin/sh -u $UID -G $(getent group "$GID" | cut -d: -f1) -h /workspace -D app >/dev/null 2>&1
fi
sudo -EHu app /usr/bin/env PATH="$PATH" "$@"
