// network-probe performs TLS-verified HTTPS requests from Android's shell UID.
// DNS goes through the device TUN; no proxy environment variables or TLS bypass.
package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func main() {
	target := flag.String("url", "https://www.google.com/generate_204", "HTTPS URL")
	count := flag.Int("count", 1, "request count")
	interval := flag.Duration("interval", time.Second, "between requests")
	timeout := flag.Duration("timeout", 15*time.Second, "request timeout")
	override := flag.String("ip", "", "optional IP override; preserves Host/TLS SNI")
	resolver := flag.String("dns", "223.5.5.5:53", "DNS target, intercepted by VPN DNS hijack")
	flag.Parse()
	roots, _ := x509.SystemCertPool()
	if roots == nil {
		roots = x509.NewCertPool()
	}
	for _, dir := range []string{"/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts"} {
		files, _ := os.ReadDir(dir)
		for _, f := range files {
			b, e := os.ReadFile(filepath.Join(dir, f.Name()))
			if e == nil {
				roots.AppendCertsFromPEM(b)
			}
		}
	}
	dns := &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "udp", *resolver)
	}}
	for i := 1; i <= *count; i++ {
		start := time.Now()
		result := map[string]any{"attempt": i, "url": *target}
		tr := &http.Transport{Proxy: nil, TLSClientConfig: &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS12}, ForceAttemptHTTP2: true, DisableKeepAlives: true}
		tr.DialContext = func(ctx context.Context, network, address string) (net.Conn, error) {
			host, port, e := net.SplitHostPort(address)
			if e != nil {
				return nil, e
			}
			ips := []string{*override}
			if *override == "" {
				ips, e = dns.LookupHost(ctx, host)
				if e != nil {
					return nil, e
				}
			}
			result["resolved"] = ips
			for _, ip := range ips {
				if net.ParseIP(ip).To4() == nil {
					continue
				}
				var conn net.Conn
				conn, e = (&net.Dialer{Timeout: *timeout}).DialContext(ctx, "tcp", net.JoinHostPort(ip, port))
				if e == nil {
					return conn, nil
				}
			}
			if e == nil {
				e = fmt.Errorf("no IPv4 address")
			}
			return nil, e
		}
		client := &http.Client{Transport: tr, Timeout: *timeout, CheckRedirect: func(r *http.Request, via []*http.Request) error {
			if len(via) > 5 {
				return fmt.Errorf("redirect limit")
			}
			return nil
		}}
		req, e := http.NewRequest("GET", *target, nil)
		if e != nil {
			panic(e)
		}
		req.Header.Set("User-Agent", "SurgeBridge-NetworkProbe/1")
		if req.URL.Hostname() == "cloudflare-dns.com" {
			req.Header.Set("Accept", "application/dns-json")
		}
		req = req.WithContext(httptrace.WithClientTrace(req.Context(), &httptrace.ClientTrace{TLSHandshakeDone: func(s tls.ConnectionState, e error) { result["tls_verified"] = e == nil && len(s.VerifiedChains) > 0 }}))
		resp, e := client.Do(req)
		if e != nil {
			result["error"] = e.Error()
		} else {
			result["status"] = resp.StatusCode
			result["protocol"] = resp.Proto
			body, _ := io.ReadAll(io.LimitReader(resp.Body, 1024*1024))
			resp.Body.Close()
			result["bytes"] = len(body)
			result["google_marker"] = strings.Contains(strings.ToLower(string(body)), "google")
			result["server"] = resp.Header.Get("Server")
			result["challenge"] = resp.Header.Get("cf-mitigated") == "challenge" || strings.Contains(string(body), "cf-chl-")
			if req.URL.Hostname() == "dns.google" || req.URL.Hostname() == "cloudflare-dns.com" {
				var answer map[string]any
				if json.Unmarshal(body, &answer) == nil {
					result["dns_status"] = answer["Status"]
					result["dns_answers"] = answer["Answer"]
				}
			}
		}
		tr.CloseIdleConnections()
		result["duration_ms"] = time.Since(start).Milliseconds()
		json.NewEncoder(os.Stdout).Encode(result)
		if i < *count {
			time.Sleep(*interval)
		}
	}
}
