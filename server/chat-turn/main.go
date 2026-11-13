// Alkamel Chat TURN relay. This process is dedicated to the chat voice feature.
// Configuration is supplied through environment variables by the Windows manager.
package main

import (
	"log"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"

	"github.com/pion/turn/v5"
)

func env(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func main() {
	listenIP := env("ALKAMEL_TURN_LISTEN_IP", "0.0.0.0")
	relayIP := env("ALKAMEL_TURN_RELAY_IP", "10.10.10.10")
	port, err := strconv.Atoi(env("ALKAMEL_TURN_PORT", "3478"))
	if err != nil || port < 1 || port > 65535 {
		log.Fatal("invalid ALKAMEL_TURN_PORT")
	}
	minPort, err := strconv.Atoi(env("ALKAMEL_TURN_MIN_PORT", "49160"))
	if err != nil || minPort < 1 || minPort > 65535 {
		log.Fatal("invalid ALKAMEL_TURN_MIN_PORT")
	}
	maxPort, err := strconv.Atoi(env("ALKAMEL_TURN_MAX_PORT", "49200"))
	if err != nil || maxPort < minPort || maxPort > 65535 {
		log.Fatal("invalid ALKAMEL_TURN_MAX_PORT")
	}
	username := strings.TrimSpace(os.Getenv("ALKAMEL_TURN_USERNAME"))
	password := os.Getenv("ALKAMEL_TURN_PASSWORD")
	if username == "" || password == "" {
		log.Fatal("TURN credentials are missing; configure them in Alkamel Chat Server")
	}
	realm := env("ALKAMEL_TURN_REALM", "alkamel-chat")

	relayAddress := net.ParseIP(relayIP)
	if relayAddress == nil {
		log.Fatal("ALKAMEL_TURN_RELAY_IP must be a literal IPv4 or IPv6 address")
	}
	listener, err := net.ListenPacket("udp4", net.JoinHostPort(listenIP, strconv.Itoa(port)))
	if err != nil {
		log.Fatalf("cannot listen for TURN on %s:%d: %v", listenIP, port, err)
	}
	defer listener.Close()

	authKey := turn.GenerateAuthKey(username, realm, password)
	server, err := turn.NewServer(turn.ServerConfig{
		Realm: realm,
		AuthHandler: func(request *turn.RequestAttributes) (string, []byte, bool) {
			if request.Username != username {
				return "", nil, false
			}
			return username, authKey, true
		},
		PacketConnConfigs: []turn.PacketConnConfig{{
			PacketConn: listener,
			RelayAddressGenerator: &turn.RelayAddressGeneratorPortRange{
				RelayAddress: relayAddress,
				Address:      listenIP,
				MinPort:      uint16(minPort),
				MaxPort:      uint16(maxPort),
			},
		}},
	})
	if err != nil {
		log.Fatalf("cannot initialize TURN relay: %v", err)
	}
	log.Printf("Alkamel chat TURN ready: listen=%s:%d relay=%s ports=%d-%d realm=%s",
		listenIP, port, relayIP, minPort, maxPort, realm)

	signals := make(chan os.Signal, 1)
	signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
	<-signals
	if err := server.Close(); err != nil {
		log.Printf("TURN shutdown warning: %v", err)
	}
}
