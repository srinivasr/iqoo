# Live-inspection relay and feature checks

- `bridge.py` + `servers.py` + `echo_tcp.py` + `NatHarness.kt`: run the Kotlin relay (`app/.../enforcement/relay`)
  against the real Linux TCP/IP stack through a TUN device (needs root / CAP_NET_ADMIN, `pip install pyroute2`).
  Then `curl --noproxy '*' http://198.18.0.1:8099/big.bin` etc. goes through the relay.
- `feat_ref.py` + `FeatHarness.kt`: Kotlin `LiveWindowFeatures` vs the `mischief_extract.py` window logic.
- `mischief_nodpkt.py`: the `mischief_extract.py` windowing without dpkt (writes windows.csv) used to check the
  live model's alert rate on the real Android Mischief captures.
