// Command piik-capture-shim is the "native capture process" Piik's Go App
// launches on Android. Android screen capture (MediaProjection) and hardware
// encoding (MediaCodec) are only reachable from the app's Java/Kotlin side,
// so this shim forwards its arguments, environment and stdin to the app's
// capture service over an abstract Unix socket, and relays stdout, stderr and
// the exit code back. Piik sees an ordinary capture process.
//
// Wire format (shim → service): one JSON line {"args":[...],"env":{...}},
// then raw stdin bytes; stdin EOF half-closes the socket.
// (service → shim): records of [type u8][length u32 BE][payload], where type
// 1 = stdout, 2 = stderr, 3 = exit (payload: one byte exit code).
package main

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"strings"
)

const (
	recordStdout = 1
	recordStderr = 2
	recordExit   = 3
	maxRecord    = 8 << 20
)

func main() {
	os.Exit(run())
}

func run() int {
	name := os.Getenv("PIIK_ANDROID_CAPTURE_SOCKET")
	if name == "" {
		fmt.Fprintln(os.Stderr, "Piik capture unavailable: PIIK_ANDROID_CAPTURE_SOCKET is not set")
		return 2
	}
	// Abstract socket on Android; a filesystem path is accepted for off-device tests.
	address := "@" + name
	if strings.HasPrefix(name, "/") {
		address = name
	}
	conn, err := net.Dial("unix", address)
	if err != nil {
		fmt.Fprintln(os.Stderr, "Piik capture unavailable: Android capture service is not running:", err)
		return 2
	}
	defer conn.Close()
	unix := conn.(*net.UnixConn)

	env := map[string]string{}
	for _, entry := range os.Environ() {
		key, value, _ := strings.Cut(entry, "=")
		if strings.HasPrefix(key, "PIIK_") && key != "PIIK_ANDROID_CAPTURE_SOCKET" {
			env[key] = value
		}
	}
	header, _ := json.Marshal(map[string]any{"args": os.Args[1:], "env": env})
	if _, err := unix.Write(append(header, '\n')); err != nil {
		fmt.Fprintln(os.Stderr, "Piik capture unavailable:", err)
		return 2
	}

	go func() {
		_, _ = io.Copy(unix, os.Stdin)
		_ = unix.CloseWrite()
	}()

	stdout := bufio.NewWriterSize(os.Stdout, 256<<10)
	reader := bufio.NewReaderSize(unix, 256<<10)
	var head [5]byte
	for {
		if _, err := io.ReadFull(reader, head[:]); err != nil {
			_ = stdout.Flush()
			return 2 // service went away without an exit status
		}
		size := binary.BigEndian.Uint32(head[1:])
		if size > maxRecord {
			_ = stdout.Flush()
			fmt.Fprintln(os.Stderr, "Piik capture unavailable: oversized record from service")
			return 2
		}
		payload := make([]byte, size)
		if _, err := io.ReadFull(reader, payload); err != nil {
			_ = stdout.Flush()
			return 2
		}
		switch head[0] {
		case recordStdout:
			if _, err := stdout.Write(payload); err != nil {
				return 2
			}
			// Flush at record boundaries so frames reach Piik immediately.
			if reader.Buffered() == 0 {
				if err := stdout.Flush(); err != nil {
					return 2
				}
			}
		case recordStderr:
			_, _ = os.Stderr.Write(payload)
		case recordExit:
			_ = stdout.Flush()
			if len(payload) == 1 {
				return int(payload[0])
			}
			return 2
		}
	}
}
