# BYD-HA

A small Android app for the BYD head unit that sends the car's data to Home Assistant over
MQTT, optionally through Tailscale. Built for the **BYD Seal U DM-i** (plug-in hybrid), so it
reports fuel and battery data side by side. It may work on other DiLink 3/4 BYDs.

Everything stays between the car and your Home Assistant. Nothing goes through BYD's cloud.

## What shows up in Home Assistant

A device called **BYD Seal U DM-i** appears by itself (MQTT discovery, no YAML), with:

| Area | Entities |
|---|---|
| Battery | Battery %, battery energy (estimate from % and battery size), EV range, charging power, charge plug (none/AC/DC/V2L), charging, plugged in |
| Fuel | Fuel level %, fuel range, total range, engine speed |
| Distance | Odometer, lifetime EV km, lifetime hybrid km, lifetime kWh and litres used, average kWh/100 km and L/100 km |
| Driving | Speed, gear, outside temperature |
| Tyres | Pressure for each wheel (kPa) |
| Trip | Trip distance and time, electricity use over the last 50 km |
| Drive | Drive mode (EV/HEV), energy mode, engine running, average speed, lifetime driving time |
| Car status | A/C on/off and fan level, sunroof position, driver door lock, handbrake (raw), sunroof state (raw) |
| Warnings | Low fuel, low 12 V, and the engine, ABS, ESP, airbag, brake, traction battery and steering fault lights |
| Other | 12 V battery voltage, phone charger (raw), scheduled charging, location (device tracker), Tailscale status, last update |

Sensors marked "raw" show the car's own number until its meaning is confirmed.
| Diagnostics | A **Send debug dump** button (see below) |

It only reads data. It cannot control the car.

## Setup

### 1. Home Assistant side
1. Install the **Mosquitto broker** add-on and the **MQTT** integration (Settings → Devices &
   services → Add integration → MQTT).
2. Create a Home Assistant user for the car (Settings → People → Users). Mosquitto accepts HA
   users as MQTT logins.
3. Install the **Tailscale** add-on on Home Assistant and log it in. Note its Tailscale IP
   (`100.x.y.z`) from the add-on page or the Tailscale admin console.
4. In the [Tailscale admin console](https://login.tailscale.com/admin/settings/keys), generate an
   **auth key** for the car. After the car joins, you can turn off key expiry for it on the
   Machines page, so it never needs logging in again.

### 2. Head unit side
1. Turn on **Wireless ADB** (ADB over network, port 5555) in the head unit's developer settings.
   This is the same requirement as Overdrive.
2. Download the APK from the [latest release](../../releases/tag/latest) and install it on the
   head unit.
3. Open **BYD HA** and fill in:
   - **Broker address**: Home Assistant's Tailscale IP, e.g. `100.101.102.103`
   - **Port**: `1883`
   - **Username / Password**: the HA user from step 1.2
   - Tick **Connect through Tailscale** and paste the **auth key**
4. Press **Save & start**. The first time, an *Allow USB debugging?* prompt appears: tick
   **Always allow** and tap **Allow**.
5. Press **Status** after a minute. You should see `mqtt: connected` and Tailscale `running`.
   The car then appears in Home Assistant under Settings → Devices & services → MQTT.

After a reboot the app starts the service again by itself.

## If a value is missing or wrong

BYD firmware differs between cars, so some readings may need tuning. Press **Send debug dump**
on the car's device page in Home Assistant (or **Data dump** in the app). It publishes every raw
value the car's SDK reports to `bydha/<id>/debug` and saves it on the head unit. Share that
output and the readings can be mapped properly.

## How it works

The BYD vehicle SDK only answers privileged callers. Like Overdrive, the app connects to the
head unit's own ADB service on `127.0.0.1:5555` and starts a small background process as the
`shell` user (`app_process`), kept alive by a watchdog script in `/data/local/tmp/bydha`. That
process:

- reads the car through the `android.hardware.bydauto.*` SDK classes every 30 seconds
  (configurable),
- runs Tailscale's own `tailscaled` in userspace mode with a local SOCKS5 proxy, so no VPN
  permission is needed,
- publishes a JSON state message, a location message and the HA discovery configs over MQTT,
  with a Last Will so HA shows the car as unavailable when it goes offline.

Topics (default id `seal_u`):

| Topic | Content |
|---|---|
| `bydha/seal_u/state` | All readings as one JSON object (retained) |
| `bydha/seal_u/location` | `latitude`, `longitude`, `gps_accuracy` (retained) |
| `bydha/seal_u/availability` | `online` / `offline` |
| `bydha/seal_u/cmd` | Send `dump` for a debug dump, `restart` to restart the service |
| `bydha/seal_u/debug` | Debug dump output |

## Building

GitHub Actions builds the APK on every push and publishes it as the `latest` release. To build
locally you need the Android SDK and Go:

```sh
tools/build-tailscale.sh     # builds app/src/main/jniLibs/arm64-v8a/libtailscale.so
./gradlew assembleRelease
```

## Credits

The knowledge of how to talk to the BYD SDK (getter names, feature ids, value ranges, and the
ADB/`app_process` approach) comes from
[Overdrive](https://github.com/yash-srivastava/Overdrive-release) by Yash Srivastava (MIT).
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
