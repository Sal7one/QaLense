"""Private loopback transport. Company/system proxies must never receive device credentials."""
from urllib.request import ProxyHandler, build_opener

_opener = build_opener(ProxyHandler({}))


def urlopen(request, timeout):
    return _opener.open(request, timeout=timeout)
