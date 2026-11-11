# Mosquitto setup for Alkamel-Net subscribers (no password prompt)

The Android client connects anonymously. Do not put the Mosquitto administrator password in the APK.

## 1. Create the ACL file

Create:

`C:\\Program Files\\Mosquitto\\alkamel-acl.conf`

with:

```conf
# All LAN chat clients can read and publish group chat/presence.
topic readwrite alkamel/chat/room

# Clients can publish private packets to a recipient topic, but may
# read only the private topic whose suffix equals their MQTT client ID.
pattern write alkamel/chat/private/+
pattern read alkamel/chat/private/%c
```

The app uses its persistent random device ID as its MQTT client ID and subscribes to
`alkamel/chat/private/<device-id>`.

## 2. Update mosquitto.conf

Keep the listener and replace `allow_anonymous false` with:

```conf
listener 1883 0.0.0.0
allow_anonymous true
acl_file C:\\Program Files\\Mosquitto\\alkamel-acl.conf
```

The old password file can remain on disk, but the Android chat app will no longer use
the administrator account or ask subscribers for a password.

## 3. Restart Mosquitto

Restart the Mosquitto Windows service once after saving both files.

## Security note

This setup removes subscriber passwords and limits MQTT topic access. The group chat
topic is readable by any device that can reach the broker on the LAN. Private-topic
read access is limited by MQTT client ID, which is not strong authentication against
a malicious device on the same LAN. For hostile/untrusted LANs, use TLS plus unique
per-device credentials provisioned by a trusted enrollment service.
