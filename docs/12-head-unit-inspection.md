# Inspecting the head unit: is there a trust anchor, and what does it check?

This document exists because measuring from the phone side has reached its
limit. From outside the car, "the head unit refused our certificate" and "the
head unit refused us for some other reason" produce the same observation: an
`0x0004` auth-complete carrying `status = -3`, followed by USB teardown. Nine
generated identities that varied structure, chain depth, validity, key size and
key algorithm all got the identical `-3`. That invariance says the certificate
is not what the head unit is deciding on — but it cannot say whether the unit is
refusing *all* certificates or refusing us for a reason that has nothing to do
with the certificate at all. See [the trust model](03-trust-model.md) for how a
closed enum once turned that same `-3` into a false `AUTHENTICATED`.

The head unit is the better source of truth, and on this hardware it can be
opened without removing it from the car. This is the field guide for doing that:
getting a shell, finding where a trust anchor would live, and — the part that
matters — stating in advance exactly which observation settles the question and
which observations only look like they do.

A warning that colours everything below: **this project has already published
one wrong conclusion from insufficient evidence.** The discipline that catches
the second one is not caution in prose. It is refusing to call anything
confirmed until the evidence rules out the alternatives. Section 3 is written to
that standard; sections 1 and 2 exist to get you to the point where section 3
can be applied.

---

## 0. First establish which unit you actually have

Everything downstream is variant-specific, and the community record is full of
confident instructions that are correct for one MIB2 and wrong for the next.
"MIB2" is a platform, not a unit. The projection stack, the shell route, the IP
address, and even whether there is a login password all differ by who built the
box and which software train it runs.

The three families that matter here:

| Family | Builder | Marketing names | Application SoC | Shell endpoint (see §1) | Login |
| --- | --- | --- | --- | --- | --- |
| **MIB2 Standard, PQ/ZR** | Technisat / Preh | Composition Media, Discover Media | i.MX6 | telnet `192.168.1.4:23` | `root` / `root` |
| **MIB2 Standard, MST2** | Delphi | Composition Media (some markets) | dual: RCC + MMX | telnet `172.16.250.248`, MMX `:23`, RCC `:123` | often passwordless |
| **MIB2 High** | Harman | Discover Pro, Audi MMI | dual: RCC + MMX | telnet `172.16.250.248` (MMX `:23`, RCC `:123`) | password is firmware-train-specific |

Sources for this split:
[olli991/mib-std2-pq-zr-toolbox](https://github.com/olli991/mib-std2-pq-zr-toolbox)
and its [install
wiki](https://github.com/olli991/mib-std2-pq-zr-toolbox/wiki/How-to-install-the-toolbox)
(Technisat/Preh PQ/ZR → `192.168.1.4`, `root`/`root`, prompt `imx6:/#`);
[jilleb/mib2-toolbox SSH-Login](https://github.com/jilleb/mib2-toolbox/wiki/SSH-Login)
and
[Alternative-network-adapters](https://github.com/jilleb/mib2-toolbox/wiki/Alternative-network-adapters)
(Harman High, root password "for your particular version of firmware", MMX node,
`172.16.250.x`);
[superkolos/Audi-MIB2-Toolbox](https://github.com/superkolos/Audi-MIB2-Toolbox)
(`172.16.250.248`, RCC `:123` / MMX `:23`, login "for your units SW train");
robpol86's [MIB2 Composition Media](https://robpol86.com/mib2_comp_media.html)
writeup (Delphi, QNX 6.5.0, RCC `:123` / MMX `:23`).

**MIB2.5** is a mid-life hardware refresh (roughly 2018+) of the same families
and is accessed the same way; the difference is board revision and software
train, not method. **MIB3** (2020+) is a different architecture — Android-based,
not QNX — and none of this applies to it. If the unit is MIB3, stop; this
document is about MIB2.

**What the 2017 Polo most likely is.** A Polo of that era with Composition Media
or Discover Media is almost certainly a **Technisat/Preh PQ Standard unit** — the
first row, i.MX6, `192.168.1.4`, `root`/`root`. That is a probability from the
model/era, not a certainty. **Confirm it from the part number and software train
before trusting any address in this document.** Read them from `Menu → Setup →
System information`, or long-press `MENU` and open `Software Update`, which shows
the train string (e.g. `MST2_EU_VW_ZR_P0105T`). A Technisat/Preh train reads
`MST2_...`; a High/Harman train reads `MHI2_...` (Audi trains) or the Harman
variant of the VW train. Get this right first — the single most common way to
waste an afternoon here is to run the Delphi instructions against a Technisat
box.

### Corrections to the assumptions this task started from

The working memory for this project was: "developer mode on 5F, enable Ethernet
on the projection USB port, the unit answers on 192.168.1.4 with FTP and an
unauthenticated telnet root shell." Checked against the record:

- **`192.168.1.4` is right, but only for the Technisat/Preh Standard unit** — which
  is the likely Polo unit, so the memory is not wrong so much as
  un-generalised. On Harman High and Delphi units the address is `172.16.250.248`
  and there are *two* shells (RCC and MMX) on different ports. Do not carry
  `192.168.1.4` to a different box.
- **Ethernet is not simply "on the projection USB port."** The unit speaks
  Ethernet only to a specific **USB-to-Ethernet dongle** plugged into the
  full-function USB port — historically a D-Link DUB-E100 (specific hardware
  revisions), and on newer trains an ASIX AX88178/AX88179. A plain USB cable to a
  laptop gets you nothing. See §1.3.
- **Telnet/FTP is disabled by default and must be enabled first.** It is not
  waiting for you on a stock unit. On the Technisat/Preh units the standard route
  is to install a toolbox that starts `inetd`; the "unauthenticated" part is only
  literally true on the passwordless Delphi MST2 units. Elsewhere it is
  `root`/`root` (Technisat/Preh) or a train-specific password (Harman).
- **"5F developer mode" is correct** and is the right starting point — verified
  below.

---

## 1. Getting a shell

Three things have to be true before a shell exists: developer mode is enabled on
the diagnostic bus, the console/telnet service is running, and you have a network
path to it. They are independent; do them in order.

### 1.1 Enable developer mode via module 5F

This is the documented, manufacturer-provided route and it is non-destructive —
it toggles a diagnostic adaptation, it does not flash anything.

1. Connect a diagnostic interface to the OBD-II port, ignition on. Any of VCDS,
   OBDeleven, VCP or ODIS can do this.
2. Open control module **5F (Information Electronics)**.
3. Go to **Adaptation** and find the channel **`IDE02122` — "Developer mode"**
   (searching the text "Developer mode" finds it).
4. Set it from *Not active* / *Not activated* to **Active**.
5. Reboot the unit (ignition cycle) if it does not take effect immediately.

Tool-specific authorisation, which trips people up:

- **OBDeleven**: works directly.
- **VCDS**: you must log in to 5F with **`S12345`** before the channel will
  write.
- **VCP**: change the session to **Engineer Mode** and authorise with **`20103`**.

After this, an extra entry appears in the service menu. Long-press **`MENU`**
(reports range from 10 to 30 seconds; hold it) to reach **Testmode → Green
Engineering Menu (GEM)**. The "green menu" and "developer menu" are the same
thing; VAG stripped much of it on MIB2 but the parts needed here remain.

Sources: [olli991 install
wiki](https://github.com/olli991/mib-std2-pq-zr-toolbox/wiki/How-to-install-the-toolbox)
(the `S12345` / `20103` authorisation and the exact menu path);
[mmiupdates](https://mmiupdates.com/tutorials/audi-mib2-hidden-green-menu-vcds-obdeleven/)
and [myaudi.org](https://myaudi.org/manuals/unlock-audi-mib2-engineering-menu-guide/)
(channel `IDE02122`, the `5F → Adaptation` path, the MENU long-press). The
`IDE02122` channel identifier is well documented across VCDS/OBDeleven guides;
treat it as **documented**.

### 1.2 Start the console/telnet service

On a stock unit the shell daemons are not running. Enabling them is the step that
genuinely modifies the unit, and it is where the community toolboxes come in. The
mechanism is plain once you see it — the toolbox's `activate_telnet.sh` simply
runs `inetd` (which brings up `telnetd` and `ftpd`) and `pfctl -d` to drop the
packet filter
([source](https://github.com/olli991/mib-std2-pq-zr-toolbox/blob/master/toolbox/gem/cpu/onlineservices/1/default/tsd/etc/persistence/esd/scripts/activate_telnet.sh)).

The chicken-and-egg problem: on a fully stock Technisat/Preh unit you cannot
telnet in to run that script, so the first access is bootstrapped another way —
by installing the toolbox from an SD card through **Service Mode → Software
Update** (`MIBStd2_Online_Approval`), or over a **UART serial console** on the
board (115200 baud), or on locked variants by soldering / a USB2HSD cable to the
eMMC. Once the toolbox is installed, its **`network`** menu item activates telnet
and FTP; after that the telnet route is available on every boot you choose to
enable it. Full bootstrap options are in the
[olli991 README](https://github.com/olli991/mib-std2-pq-zr-toolbox#readme).

For pure **inspection** you do not need to keep any of this. You need read
access once. Everything you touch here is reversible (§4); the point of naming
the mechanism is that "enable telnet" is not a single button on a stock box.

### 1.3 Get a network path in

The unit talks Ethernet only to a recognised USB-Ethernet dongle in the
full-function USB port. Which dongles are recognised depends on the firmware
train, and this is where a lot of failed attempts come from:

- **Older/MMX1 and the Standard units**: only the **D-Link DUB-E100** in the
  right hardware revision (rev B/C for some, **rev D1** cited for the Delphi/High
  units). Wrong revision, no link.
- **Newer/MMX2 trains**: the driver map (`usblauncher.lua`, calling
  `extnet.sh` with `devnp-asix.so`) also binds **ASIX AX88178 (0x0b95/0x1780)**
  and **AX88179 (0x0b95/0x1790)**, which are far easier to buy — the AX88179 is
  the common "Nintendo Switch" USB3 adapter.
  ([Alternative-network-adapters](https://github.com/jilleb/mib2-toolbox/wiki/Alternative-network-adapters),
  reporting driver bindings extracted from `efs-system.img` and a confirmed
  working AX88179.)

Then, by family:

- **Technisat/Preh Standard**: set your laptop NIC to a static `192.168.1.x`
  (not `.4`), telnet to **`192.168.1.4` port 23**, log in **`root`/`root`**. A
  successful login shows the prompt **`imx6:/#`**
  ([install wiki §4.3.2](https://github.com/olli991/mib-std2-pq-zr-toolbox/wiki/How-to-install-the-toolbox)).
- **Harman High / Delphi**: the unit appears as interface `en0`; read its exact
  address from the green menu at **`production → mmx_prod → ip-setting_prod →
  IP-Address`** — reported as **`172.16.250.248`**, with the laptop on
  `172.16.250.123`. Telnet **MMX on `:23`**, **RCC on `:123`**. Login is the
  firmware-train-specific password (Harman) or none (Delphi MST2)
  ([SSH-Login](https://github.com/jilleb/mib2-toolbox/wiki/SSH-Login),
  [superkolos](https://github.com/superkolos/Audi-MIB2-Toolbox),
  [robpol86](https://robpol86.com/mib2_comp_media.html)).

A note on the two shells on the dual-processor units: **RCC** (Radio Car
Controller) and **MMX** (the multimedia/application processor) are separate QNX
nodes reachable as `/net/rcc` and `/net/mmx` from either shell. **Projection runs
on MMX.** Wi-Fi, where present, is an alternative to the dongle (`mlan0` client,
`uap0` hotspot), and the jilleb toolbox can install an `sshd` so you can use SSH
over Wi-Fi instead of telnet — convenient but a bigger modification than starting
`inetd`.

**A cleaner, lower-touch alternative to a live shell:** dump the filesystem and
inspect it on a PC. The FEC/analysis tooling for exactly this exists —
`dumpefs` + `extract_efs.py` unpack a QNX EFS image, and `dumpifs`/`mkxfs` from
the QNX SDP unpack the `ifs-root` image
([bdrr77/MIB2](https://github.com/bdrr77/MIB2)). Reading a copied image off the
car changes nothing on the car and is the safest way to satisfy §2 and §3. If a
shell is available, `tar` or `scp` the projection directory off and work from the
copy.

---

## 2. What to look for once inside

The projection stack on MIB2 is, by report, a supervisor over separate Android
Auto, CarPlay and MirrorLink clients, all on the MMX/QNX side. The honest state
of the public record: **the config paths are documented, but the exact projection
binary and library names are not published anywhere reliable.** Do not trust a
confidently-named binary from a forum post, and do not fill the gap with a guess.
The method below finds the real names on the unit rather than assuming them.

### 2.1 Orient: filesystem and processes

QNX layout you will be working in:

- `/tsd/etc/...` — application configuration and data. MirrorLink's config lives
  at **`/tsd/etc/mirrorlink/mirrorlink.config.common.xml`**, which is the anchor
  point: the CarPlay and Android Auto configs and binaries sit in the same region
  of the tree
  ([patch_mirrorlink.sh](https://github.com/olli991/mib-std2-pq-zr-toolbox/blob/master/toolbox/gem/cpu/onlineservices/1/default/tsd/etc/persistence/esd/scripts/patch_mirrorlink.sh)).
- `/mnt/app`, `/mnt/system`, `/fs/sda0`, `/fs/sda1` — main partitions,
  **read-only by default**; the SSH-Login wiki documents remounting `-uw` if you
  ever need write (you do not, for inspection).
- `/net/mmx/...` and `/net/rcc/...` — the two nodes, on dual-processor units.

Find the running projection processes rather than guessing their names:

```
pidin -F "%b %n %A"        # QNX process list: names and arguments
pidin arg | grep -iE 'gal|aap|android|carplay|mirror|proj|smart|app.?connect'
ls -la /tsd/etc            # config dirs name the components
```

`pidin` is QNX's `ps`. The process arguments frequently reveal the config file a
daemon was started with, which points you straight at its directory. The
directory that holds the Android Auto config holds the binary that reads it.

### 2.2 Where a trust anchor would live, if there is one

Android Auto's session is mutual TLS in which **the phone is the server** and
presents the certificate under scrutiny; the head unit is the TLS client and is
the party that would carry a trust anchor for the phone. Every phone-side leaf in
public circulation chains to one self-signed root — `C=US, ST=California, L=Mountain
View, O=Google Automotive Link`, RSA-2048, SHA-1 self-signature (see
[03-trust-model.md](03-trust-model.md)). If this unit pins that root, a copy of
**that public certificate** is somewhere in the projection component, and the
verify code compares against it.

MIB2 already stores trust anchors as PEM files for an unrelated purpose — the VW
online-services backend ships a directory of `ca*.pem` roots
(`ca.pem`, `ca09.pem`, `ca0c.pem`, …) selected per market
([OnlineCAI.esd](https://github.com/olli991/mib-std2-pq-zr-toolbox/blob/master/toolbox/devesd/OnlineCAI.esd)).
That tells you the *convention*: a bundled CA on this platform looks like a PEM
(or DER) file next to the component that uses it. A projection trust anchor, if
present, would most plausibly be:

1. **A PEM/DER file** in the Android Auto component's directory (or a shared
   `certs`/`ssl` dir), or
2. **Compiled into the binary** as an embedded blob — common for a single pinned
   root, because it cannot be swapped out on the filesystem.

Commands to look for each, working from a copied image or a read-only shell:

```
# 2.2a — loose certificate files anywhere under the projection tree
find /tsd /mnt/app /opt -type f \( -name '*.pem' -o -name '*.der' -o -name '*.crt' -o -name '*.cer' \) 2>/dev/null

# 2.2b — decode any candidate and check the subject/issuer
openssl x509 -in <file> -noout -subject -issuer -dates -fingerprint -sha1
openssl x509 -in <file> -inform DER -noout -subject -issuer   # if PEM parse fails

# 2.2c — a cert embedded in a binary: PEM armour, or a DER SubjectPublicKeyInfo
strings -a <binary> | grep -n 'BEGIN CERTIFICATE'
strings -a <binary> | grep -iE 'Google Automotive Link|Mountain View'

# 2.2d — the verification machinery the binary was built with
strings -a <binary> | grep -iE 'SSL_CTX|X509_verify|verify_callback|CApath|CAfile|SSL_VERIFY|set_verify|PinnedCert|trust'
# symbols, if not stripped:
nm -D <binary> 2>/dev/null | grep -iE 'verify|x509|ssl_ctx'
```

The tell for **certificate pinning** versus **none** is not the presence of a
cert file — it is which of these two shapes the code takes:

| You find | Reading |
| --- | --- |
| `SSL_CTX_set_verify(..., SSL_VERIFY_PEER, cb)` with a CA store loaded from a file/dir that contains the GAL root, or a hardcoded issuer/fingerprint comparison against it | it validates the chain against Google's root — **pinning** |
| `SSL_VERIFY_NONE`, or a verify callback that returns `1`/`ok` unconditionally, or no CA store ever loaded on the projection TLS context | it does not validate the phone's identity — **no pinning** |

### 2.3 The decisive artifact: what `-3` means

The single most valuable thing in the binary is not the certificate. It is the
**definition of the auth-complete status code**. The field measurement is a
`status = -3` delivered in the AAP `0x0004` message *after* the tunnelled TLS
handshake completed — i.e. the head unit does not reject us with a TLS alert, it
completes the handshake exchange and reports its verdict as an application-level
status. So `-3` is the head unit's own word for why it refused, and its meaning
is written down in the binary that produced it.

```
strings -a <binary> | grep -niE 'auth.?complet|status|reason|-3|0xfffffffd|E_|ERR_'
# then trace the constant near the auth-complete handler and the verify call site
```

If you can map `-3` to a named constant (an error enum, a log string, a reason
code) **and** show whether the certificate-verify result feeds it, you have
answered the question directly — without inferring anything from the phone side.
That correlation is the goal of the whole exercise.

---

## 3. What actually answers the question

This is the section written to the standard the retraction demands. Read it
before concluding anything. The three categories below are deliberately
separated because the project's previous error was putting an observation in the
first column that belonged in the third.

### Confirms "this unit pins Google's root"

All of the following, together, not any one alone:

1. The projection binary or a file it loads **contains the Google Automotive Link
   root** — a certificate whose subject is exactly `C=US, ST=California, L=Mountain
   View, O=Google Automotive Link`, RSA-2048, SHA-1 self-signed, matching the
   fingerprint in [03-trust-model.md](03-trust-model.md). (This is *public*
   material — identifying it is fine; see §5.)
2. The TLS client context for the projection session is built with
   **`SSL_VERIFY_PEER`** (or an equivalent enforced verify), against a trust store
   whose only relevant anchor is that root.
3. The verify result **gates the session** — a failed chain leads to the
   auth-complete `status = -3` (or whatever `-3` decodes to) and teardown.

Point 3 is what makes it a *confirmation* rather than a *finding of a cert on
disk*. You must connect the anchor to the refusal.

### Refutes it

Any one of these, provided you have shown it is the path the projection session
actually takes:

- The projection TLS context uses **`SSL_VERIFY_NONE`**, or a verify callback
  that always returns success.
- **No trust anchor is loaded** on that context and no manual chain check exists.
- `-3` decodes to something **unrelated to certificate validation** (e.g. a
  version, feature-licence, component-protection, or capability mismatch), and the
  certificate is never verified — in which case the certificate was never the
  wall and the phone-side probe matrix has been chasing the wrong layer.

That last one is not hypothetical. The head unit completing the handshake and
*then* sending `-3` is exactly the signature of a rejection that happens above
TLS. It is a live possibility that the refusal has nothing to do with the trust
anchor, and the binary is where that gets settled.

### Inconclusive — do not publish on any of these

- **A `*.pem` trust anchor exists on the filesystem** but you have not shown it is
  loaded by the projection TLS context. MIB2 carries CA files for online services
  and for MirrorLink; finding *a* cert proves nothing about Android Auto.
- **`strings` shows OpenSSL and verify symbols** but you cannot show the runtime
  trust store contents or that the verify path is reached. A binary can contain
  `X509_verify` and still call it with verification disabled.
- **The GAL root's subject string appears in the binary** but only in a
  diagnostic/log table, not wired into a verify call.
- **`-3` is observed but not decoded.** The whole retraction is about `-3` having
  been misread once. An undecoded `-3` is not evidence for either side.
- **Anything read from a different MIB2 family** than the car in question. A
  Harman High binary does not testify about a Technisat Standard unit.

The rule: presence is not enforcement, and a symbol is not a code path. Confirm
the anchor is loaded *and* that the verify result reaches the refusal, or write
it down as still open.

---

## 4. Risk, honestly

Read this before touching the car. Some of it is genuinely irreversible without a
dealer.

### Reversible, low risk — the whole inspection path

- **Enabling developer mode on 5F** (`IDE02122`) is a diagnostic adaptation. Set
  it back to *Not active* when done. It does not flash firmware and does not touch
  component protection.
- **Starting `inetd`/telnet, reading files, copying an image off** change nothing
  persistent if you do not write to the mounted partitions. The partitions are
  read-only by default; leave them that way. An ignition cycle returns a unit that
  only had telnet started to its normal state.
- **Dumping the filesystem and inspecting on a PC** is the zero-risk path and is
  preferred. It cannot brick anything.

### Dangerous — not needed for inspection, listed so you do not wander into it

- **Flashing firmware (SWDL), patching `ifs-root`/`MIBRoot`, or writing to
  `/fs/sda*` / `/mnt/app`.** The FEC and root-image tooling
  ([bdrr77/MIB2](https://github.com/bdrr77/MIB2)) exists to *defeat signature
  checks* — patching `MIBRoot` to ignore a FEC signature, re-flashing a modified
  `ifs` with `flashit`. A bad flash of the bootable image bricks the unit.
  Inspection requires **none** of this. If a procedure tells you to `flashunlock`,
  `flashit`, or patch `MIBRoot`, you have left the inspection path.
- **Writing the EEPROM / component-protection region** (`modifyE2P`, the CP
  blocks) throws fault codes and can disable the unit. Do not.

### Component protection — the expensive trap

Component Protection (CP) pairs the head unit to the car's VIN. **Reading the
filesystem does not touch CP.** CP becomes a problem when a unit is moved to a
different VIN, or when adaptation/coding that CP covers is altered:

- With CP active and a VIN mismatch, the unit powers on but audio and functions
  are disabled.
- **Clearing or re-pairing CP requires a dealer/specialist with ODIS and a live
  online connection** to the VW/Audi FAZIT/SVM backend. It is not a
  driveway/VCDS fix once the unit is locked.
- Expect real cost and a workshop visit. Sources:
  [mytiguan CP thread](https://www.mytiguan.com/threads/component-protection-mib2.54143/),
  [golfmk7 FeC/SWaP thread](https://www.golfmk7.com/forums/index.php?threads/mib-retrofits-fec-swap-hacked-vs-legit.342683/).

**The safe reading of all this: inspection is low-risk; modification is not.**
This document only needs inspection. Stay on the read-only path, prefer an
offline image dump, and CP never enters the picture.

### Also worth stating plainly

Enabling developer mode and shell access on a car you drive is a lasting change
to the unit's security posture until you undo it. Turn the adaptation off and
stop the services when the inspection is finished. Do the work with the ignition
on and the engine off (the green menu also exposes hardware settings that have no
business being changed).

---

## 5. Legal footing, and its limits

The framing this project relies on — EU **Directive 2009/24/EC Article 6** and US
**17 U.S.C. §1201(f)**, both as interoperability research on the user's own
vehicle — holds for what this document describes, with limits that must be stated
accurately. See [07-provenance.md](07-provenance.md) for the project-wide rules;
this is the head-unit-specific reading.

**What the framing covers.**

- **EU Art. 6** permits reproducing and translating code where indispensable to
  obtain the information necessary for the interoperability of an independently
  created program. Inspecting your own head unit to learn *what it validates* — so
  a clean-room phone side can interoperate — is the textbook case. **Art. 5(3)**
  separately lets a lawful user observe, study and test to determine the ideas
  underlying the program, which squarely covers reading strings and configs.
  **Art. 8** voids any contract term purporting to forbid this.
- **US §1201(f)** permits circumventing an access control, and developing the
  means to, for the sole purpose of identifying and analysing the elements needed
  for interoperability of an independently created program. Note it is even less
  of a stretch here than usual: developer mode is a **manufacturer-provided
  diagnostic function**, so the shell route is arguably not "circumvention" at
  all. Independently, the Librarian of Congress's recurring **§1201 exemption for
  land-vehicle software** (diagnosis, repair and lawful modification by the
  owner) covers this class of activity on your own car.

**What it does not cover — and this is where the project's footing lives or
dies.**

- **It does not authorise redistributing extracted proprietary code.** EU Art.
  6(2) forbids using the obtained information for anything other than
  interoperability and forbids passing it to others beyond that need; §1201(f)(3)
  lets you share information *solely* to enable interoperability, not to publish
  the firmware. The Android Auto binary, the projection libraries, and the
  images you dump are copyrighted and must stay on your bench. Findings —
  *"the unit pins the GAL root", "-3 decodes to X"* — may be published; the
  binaries they came from may not.
- **It does not authorise touching private key material.** A private key is an
  access credential, not an interface element; extracting or redistributing one
  falls outside §1201(f) and looks like trafficking under §1201(a)(2), and the key
  at issue is almost certainly a trade secret besides. **Identifying a *public*
  trust anchor is fine** — the GAL *root certificate* is a public object and
  naming it, fingerprinting it, or observing that the unit checks against it
  carries no key material. The line is bright: public certificate, yes; any
  private key, never.
- **Interoperability only.** The purpose that makes this lawful is building an
  independently created interoperating program. It is not a license for
  circumvention for its own sake, and it does not extend to defeating component
  protection or feature licensing.

The clean-room boundary from [07-provenance.md](07-provenance.md) applies without
exception here: **do not open the leaked Head Unit Integration Guide** (hosted at
`milek7.pl/.stuff/galdocs/`) even though searches surface it — it is confidential
material and reading it is trade-secret exposure, not a copyright question a
clean-room split could launder. Everything in this document was assembled from
open-source toolboxes and community reverse-engineering read as facts, and none
of it needs that guide.

---

## The one-paragraph version

Confirm the unit is a Technisat/Preh Standard MIB2 from its part number and
`MST2_...` train (the likely Polo unit), not a Harman High or Delphi box, because
the address and login differ. Enable developer mode on module **5F**, adaptation
**`IDE02122`**; start telnet; reach the shell at **`192.168.1.4:23`**, `root`/`root`,
via a recognised USB-Ethernet dongle — or better, dump the filesystem and inspect
it offline. Find the projection binary by its running config (`pidin`, `/tsd/etc`),
look for the **GAL root** as a PEM/DER file or an embedded blob, and read the TLS
verify path. **Confirmation requires all three of: the root is present, the verify
context enforces it, and its result drives the `-3` refusal.** A cert on disk, a
verify symbol, or an undecoded `-3` is not confirmation — the last time this
project treated "not confirmed" as "confirmed", it had to retract. Inspection is
low-risk and reversible; flashing, `MIBRoot` patching and component protection are
not, and none of them are needed to answer the question. Public trust anchor: fine
to identify. Private keys and extracted binaries: never leave the bench.
