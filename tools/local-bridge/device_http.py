"""Private loopback transport. Company/system proxies must never receive device credentials."""
from urllib.request import HTTPRedirectHandler, ProxyHandler, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Credentials belong to the original adb forward only.


_opener = build_opener(ProxyHandler({}), NoRedirect())


def urlopen(request, timeout):
    return _opener.open(request, timeout=timeout)
