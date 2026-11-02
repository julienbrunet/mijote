package auth

import (
	"net"
	"net/http"
	"net/netip"
	"strings"
)

// Proxies lists the trusted reverse proxies whose X-Forwarded-* headers are believed.
type Proxies []netip.Prefix

// ParseProxies reads a list such as "192.168.1.10, 10.0.0.0/8".
func ParseProxies(s string) (Proxies, error) {
	var out Proxies
	for _, f := range strings.FieldsFunc(s, func(r rune) bool { return r == ',' || r == ' ' }) {
		if !strings.Contains(f, "/") {
			a, err := netip.ParseAddr(f)
			if err != nil {
				return nil, err
			}
			out = append(out, netip.PrefixFrom(a, a.BitLen()))
			continue
		}
		p, err := netip.ParsePrefix(f)
		if err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, nil
}

func (p Proxies) trusted(a netip.Addr) bool {
	for _, pr := range p {
		if pr.Contains(a.Unmap()) {
			return true
		}
	}
	return false
}

// ClientIP returns the client address. X-Forwarded-For is only read when the
// connection comes from a trusted proxy; the chain is then walked from the right
// up to the first untrusted hop (nginx: proxy_add_x_forwarded_for).
func (p Proxies) ClientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	addr, err := netip.ParseAddr(host)
	if err != nil || !p.trusted(addr) {
		return host
	}
	hops := strings.Split(r.Header.Get("X-Forwarded-For"), ",")
	for i := len(hops) - 1; i >= 0; i-- {
		h := strings.TrimSpace(hops[i])
		a, err := netip.ParseAddr(h)
		if err != nil {
			break
		}
		if !p.trusted(a) {
			return a.Unmap().String()
		}
		host = h
	}
	return host
}

// IsHTTPS reports whether the request came over HTTPS, directly or through a trusted proxy.
func (p Proxies) IsHTTPS(r *http.Request) bool {
	if r.TLS != nil {
		return true
	}
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	addr, err := netip.ParseAddr(host)
	return err == nil && p.trusted(addr) && strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https")
}

// PublicURL is the address of the server as the client sees it ("https://host"),
// used in the invitation links.
func (p Proxies) PublicURL(r *http.Request) string {
	if p.IsHTTPS(r) {
		return "https://" + r.Host
	}
	return "http://" + r.Host
}
