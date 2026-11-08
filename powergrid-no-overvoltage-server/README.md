# Create: Power Grid 0.6.2 server build

This NeoForge build is for Minecraft 1.21.1. It is based on the Power Grid
`architectury-1.21.1/dev` source branch at commit
`0938be067fb71f311b3e5fd6c5f7cdaed722e241`, with the focused changes in
[`changes.patch`](./changes.patch).

## Changes

- Switch overvoltage failures now follow the existing server-side
  `electricity.overheating` setting.
- Light-bulb burnout now follows that same setting.

Set `overheating = false` in the server's `config/powergrid-server.toml` and
restart the server to disable those failures. This does not change the separate
wire-overheating setting.

## Client compatibility

The mod ID and declared version remain `powergrid` and `0.6.2`, and no
networking protocol or config field was added. Clients should retain the
standard Power Grid 0.6.2 jar; this is intended to be installed only on the
NeoForge server. A live connection test with an unmodified client was not
performed.

## Build and release

The server jar is published in the GitHub Release assets. The release asset is
for NeoForge, not Fabric. The original client jar should not be replaced with
this server build.

The source project is licensed under Apache-2.0; see
[`UPSTREAM-LICENSE`](./UPSTREAM-LICENSE) and [`NOTICE`](./NOTICE).
