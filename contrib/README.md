# Contrib Notes #

This directory contains a unified systemd service unit file for GitBucket.
The `linux` directory contains SELinux policies for RedHat-based systems.

To install GitBucket as a systemd service:

  1. Copy `gitbucket.service` to `/etc/systemd/system/gitbucket.service`.
  2. Edit the environment variables in the service file or create `/etc/default/gitbucket` to suit your environment.
  3. Run `systemctl daemon-reload`.
  4. Run `systemctl enable --now gitbucket.service`.
