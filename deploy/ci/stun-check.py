#!/usr/bin/env python3
"""কল সার্ভারের TURN/STUN (UDP 3478) সাড়া দেয় কি না — একটা STUN Binding Request পাঠিয়ে উত্তর দেখা।

    python3 deploy/ci/stun-check.py <host> [port=3478] [timeout=4]

ঠিক থাকলে exit 0 আর stdout-এ "ok <আমাদের-দেখা-ঠিকানা>"; না হলে exit 1 আর কারণ। (RFC 5389; শুধু standard library)
"""
import os
import socket
import struct
import sys

MAGIC = 0x2112A442


def check(host: str, port: int = 3478, timeout: float = 4.0, tries: int = 3) -> str:
    addr = socket.getaddrinfo(host, port, socket.AF_INET, socket.SOCK_DGRAM)[0][4]
    last = "no answer"
    for _ in range(tries):
        tid = os.urandom(12)
        req = struct.pack("!HHI", 0x0001, 0, MAGIC) + tid
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(timeout)
        try:
            s.sendto(req, addr)
            data, _ = s.recvfrom(2048)
        except OSError as e:  # timeout বা ICMP unreachable
            last = f"no answer ({e.__class__.__name__})"
            continue
        finally:
            s.close()
        if len(data) < 20:
            last = "short reply"
            continue
        mtype, mlen, cookie = struct.unpack("!HHI", data[:8])
        if cookie != MAGIC or data[8:20] != tid:
            last = "unexpected reply"
            continue
        if mtype != 0x0101:
            last = f"not a success response (type 0x{mtype:04x})"
            continue
        i, end = 20, 20 + mlen
        while i + 4 <= min(end, len(data)):
            atype, alen = struct.unpack("!HH", data[i:i + 4])
            val = data[i + 4:i + 4 + alen]
            if atype == 0x0020 and alen >= 8 and val[1] == 0x01:  # XOR-MAPPED-ADDRESS, IPv4
                p = struct.unpack("!H", val[2:4])[0] ^ (MAGIC >> 16)
                ip = struct.unpack("!I", val[4:8])[0] ^ MAGIC
                return f"{socket.inet_ntoa(struct.pack('!I', ip))}:{p}"
            i += 4 + alen + ((4 - alen % 4) % 4)
        return "mapped-address-missing"
    raise RuntimeError(last)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    host = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 3478
    timeout = float(sys.argv[3]) if len(sys.argv) > 3 else 4.0
    try:
        print("ok", check(host, port, timeout))
    except Exception as e:  # noqa: BLE001
        print(f"fail {e}")
        sys.exit(1)
