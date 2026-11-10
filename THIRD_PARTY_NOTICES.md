# Third-party notices

## Overdrive (MIT)
Parts of this app (the BYD SDK getter names, feature ids, value ranges and sentinel values in
`VehicleReader.java` and `Byd.java`, the permission-check wrapper in `DaemonContext.java`, and
the ADB + `app_process` + watchdog approach) are adapted from Overdrive.

https://github.com/yash-srivastava/Overdrive-release

```
MIT License

Copyright (c) 2026 Yash Srivastava

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Overdrive's `tools/patches/tailscale-tcp-socket.patch` is also used as-is to build Tailscale (it moves the
local API to loopback TCP); same MIT notice.

## Bundled and linked components

| Component | Use | License |
|---|---|---|
| [Tailscale](https://github.com/tailscale/tailscale) | `libtailscale.so`, built from upstream source in CI with the patch in `tools/patches` (loopback-TCP local API, ACME resolver fallback) | BSD-3-Clause |
| [Eclipse Paho MQTT v3](https://github.com/eclipse/paho.mqtt.java) | MQTT client | EPL-2.0 / EDL-1.0 |
| [dadb](https://github.com/mobile-dev-inc/dadb) | ADB client used to start the background service | Apache-2.0 |
| Kotlin standard library, Okio (via dadb) | Runtime libraries | Apache-2.0 |
