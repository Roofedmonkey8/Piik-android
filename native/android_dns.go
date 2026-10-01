//go:build android

package main

import (
	"net"
	"os"
	"strings"
	_ "unsafe" // go:linkname
)

// Pure-Go binaries on Android have no /etc/resolv.conf, so Go's resolver
// falls back to 127.0.0.1:53 and every lookup fails (golang/go#10714).
// The Android app passes the active network's DNS servers in
// PIIK_DNS_SERVERS; public resolvers remain as a fallback.
//
// net.defaultNS is explicitly published for go:linkname (golang/go#67401).
//
//go:linkname defaultNS net.defaultNS
var defaultNS []string

func init() {
	var servers []string
	for _, value := range strings.Split(os.Getenv("PIIK_DNS_SERVERS"), ",") {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		if ip := net.ParseIP(value); ip != nil {
			servers = append(servers, net.JoinHostPort(ip.String(), "53"))
		}
	}
	defaultNS = append(servers, "1.1.1.1:53", "8.8.8.8:53", "[2606:4700:4700::1111]:53")
}
