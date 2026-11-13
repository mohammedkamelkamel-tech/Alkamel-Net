// Alkamel Chat TURN relay with short-lived TURN REST credentials.
package main

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"encoding/json"
	"sync"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/pion/turn/v5"
)

func env(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func turnCredential(secret, username string) string {
	mac := hmac.New(sha1.New, []byte(secret))
	_, _ = mac.Write([]byte(username))
	return base64.StdEncoding.EncodeToString(mac.Sum(nil))
}


type voiceClient struct {
	address *net.UDPAddr
	lastSeen time.Time
}

func startVoiceRelay(listenIP string, port int) {
	address := net.UDPAddr{IP: net.ParseIP(listenIP), Port: port}
	conn, err := net.ListenUDP("udp4", &address)
	if err != nil { log.Fatalf("cannot start voice UDP relay on %s:%d: %v", listenIP, port, err) }
	log.Printf("Alkamel voice UDP relay ready on %s:%d", listenIP, port)
	clients := make(map[string]voiceClient)
	var mu sync.Mutex
	go func() {
		buffer := make([]byte, 1500)
		for {
			n, source, readErr := conn.ReadFromUDP(buffer)
			if readErr != nil { log.Printf("voice relay read stopped: %v", readErr); return }
			if n < 2 || n > 1400 { continue }
			separator := -1
			for i := 0; i < n; i++ { if buffer[i] == '\n' { separator = i; break } }
			if separator < 1 || separator > 64 { continue }
			clientID := string(buffer[:separator])
			packet := append([]byte(nil), buffer[:n]...)
			now := time.Now()
			mu.Lock()
			for id, client := range clients { if now.Sub(client.lastSeen) > 30*time.Second { delete(clients, id) } }
			clients[clientID] = voiceClient{address: source, lastSeen: now}
			recipients := make([]*net.UDPAddr, 0, len(clients))
			if separator+1 < n {
				for id, client := range clients {
					if id != clientID && now.Sub(client.lastSeen) <= 30*time.Second {
						recipients = append(recipients, client.address)
					}
				}
			}
			mu.Unlock()
			for _, recipient := range recipients {
				if _, writeErr := conn.WriteToUDP(packet, recipient); writeErr != nil {
					log.Printf("voice relay forward failed: %v", writeErr)
				}
			}
		}
	}()
}

func main() {
	listenIP := env("ALKAMEL_TURN_LISTEN_IP", "0.0.0.0")
	relayIP := env("ALKAMEL_TURN_RELAY_IP", "10.10.10.10")
	voicePort, err := strconv.Atoi(env("ALKAMEL_VOICE_RELAY_PORT", "39128"))
	if err != nil || voicePort < 1 || voicePort > 65535 { log.Fatal("invalid ALKAMEL_VOICE_RELAY_PORT") }
	port, err := strconv.Atoi(env("ALKAMEL_TURN_PORT", "3478"))
	if err != nil || port < 1 || port > 65535 { log.Fatal("invalid ALKAMEL_TURN_PORT") }
	credentialsPort, err := strconv.Atoi(env("ALKAMEL_TURN_CREDENTIALS_PORT", "3479"))
	if err != nil || credentialsPort < 1 || credentialsPort > 65535 { log.Fatal("invalid ALKAMEL_TURN_CREDENTIALS_PORT") }
	minPort, err := strconv.Atoi(env("ALKAMEL_TURN_MIN_PORT", "49160"))
	if err != nil || minPort < 1 || minPort > 65535 { log.Fatal("invalid ALKAMEL_TURN_MIN_PORT") }
	maxPort, err := strconv.Atoi(env("ALKAMEL_TURN_MAX_PORT", "49200"))
	if err != nil || maxPort < minPort || maxPort > 65535 { log.Fatal("invalid ALKAMEL_TURN_MAX_PORT") }
	username := strings.TrimSpace(os.Getenv("ALKAMEL_TURN_USERNAME"))
	secret := os.Getenv("ALKAMEL_TURN_PASSWORD")
	if username == "" || secret == "" { log.Fatal("TURN credentials are missing; configure them in Alkamel Chat Server") }
	realm := env("ALKAMEL_TURN_REALM", "alkamel-chat")
	startVoiceRelay(listenIP, voicePort)
	relayAddress := net.ParseIP(relayIP)
	if relayAddress == nil { log.Fatal("ALKAMEL_TURN_RELAY_IP must be a literal IPv4 or IPv6 address") }

	listener, err := net.ListenPacket("udp4", net.JoinHostPort(listenIP, strconv.Itoa(port)))
	if err != nil { log.Fatalf("cannot listen for TURN on %s:%d: %v", listenIP, port, err) }
	defer listener.Close()

	server, err := turn.NewServer(turn.ServerConfig{
		Realm: realm,
		AuthHandler: func(request *turn.RequestAttributes) (string, []byte, bool) {
			parts := strings.SplitN(request.Username, ":", 2)
			if len(parts) != 2 { return "", nil, false }
			expires, parseErr := strconv.ParseInt(parts[0], 10, 64)
			now := time.Now().Unix()
			if parseErr != nil || expires <= now || expires > now+15*60 { return "", nil, false }
			password := turnCredential(secret, request.Username)
			return request.Username, turn.GenerateAuthKey(request.Username, realm, password), true
		},
		PacketConnConfigs: []turn.PacketConnConfig{{
			PacketConn: listener,
			RelayAddressGenerator: &turn.RelayAddressGeneratorPortRange{
				RelayAddress: relayAddress,
				Address: listenIP,
				MinPort: uint16(minPort),
				MaxPort: uint16(maxPort),
			},
		}},
	})
	if err != nil { log.Fatalf("cannot initialize TURN relay: %v", err) }

	mux := http.NewServeMux()
	mux.HandleFunc("/credentials", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet { http.Error(w, "method not allowed", http.StatusMethodNotAllowed); return }
		expires := time.Now().Add(10 * time.Minute).Unix()
		turnUser := strconv.FormatInt(expires, 10) + ":" + username
		response := struct {
			Username string `json:"username"`
			Credential string `json:"credential"`
			ExpiresAt int64 `json:"expiresAt"`
		}{turnUser, turnCredential(secret, turnUser), expires}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store, no-cache, must-revalidate")
		_ = json.NewEncoder(w).Encode(response)
	})
	httpServer := &http.Server{Addr: net.JoinHostPort(listenIP, strconv.Itoa(credentialsPort)), Handler: mux, ReadHeaderTimeout: 3 * time.Second}
	go func() {
		log.Printf("TURN credentials endpoint ready on %s:%d", listenIP, credentialsPort)
		if serveErr := httpServer.ListenAndServe(); serveErr != nil && serveErr != http.ErrServerClosed { log.Printf("credentials endpoint stopped: %v", serveErr) }
	}()

	log.Printf("Alkamel chat TURN ready: listen=%s:%d relay=%s ports=%d-%d realm=%s", listenIP, port, relayIP, minPort, maxPort, realm)
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
	<-signals
	_ = httpServer.Close()
	if err := server.Close(); err != nil { log.Printf("TURN shutdown warning: %v", err) }
	fmt.Println("Alkamel TURN stopped")
}
