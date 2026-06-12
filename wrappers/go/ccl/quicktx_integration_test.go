package ccl

import (
	"bufio"
	"bytes"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"
)

// Yaci DevKit integration tests for QuickTx.
//
// Requires:
// - Yaci DevKit running on port 10000
// - Native library built: ./gradlew :core:nativeCompile
//
// Run with:
//   cd wrappers/go && go test -v -run Integration ./ccl/

const devkitURL = "http://localhost:10000/local-cluster/api"
const devkitProviderURL = "http://localhost:10000/local-cluster/api"

// devkitAvailable checks if Yaci DevKit is running.
func devkitAvailable() bool {
	client := &http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get(devkitURL + "/admin/devnet")
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode == 200
}

func devkitReset() {
	req, _ := http.NewRequest("POST", devkitURL+"/admin/devnet/reset", nil)
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err == nil {
		resp.Body.Close()
	}
}

func devkitTopup(address string, adaAmount int) error {
	body, _ := json.Marshal(map[string]interface{}{
		"address":   address,
		"adaAmount": adaAmount,
	})
	resp, err := http.Post(devkitURL+"/addresses/topup", "application/json", bytes.NewReader(body))
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("topup failed (%d): %s", resp.StatusCode, string(b))
	}
	return nil
}

func devkitGetUtxos(address string) ([]map[string]interface{}, error) {
	resp, err := http.Get(devkitURL + "/addresses/" + address + "/utxos")
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	var utxos []map[string]interface{}
	if err := json.NewDecoder(resp.Body).Decode(&utxos); err != nil {
		return nil, err
	}
	return utxos, nil
}

func devkitGetProtocolParams() (map[string]interface{}, error) {
	resp, err := http.Get(devkitURL + "/epochs/parameters")
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	var pp map[string]interface{}
	if err := json.NewDecoder(resp.Body).Decode(&pp); err != nil {
		return nil, err
	}
	return pp, nil
}

func devkitSubmitTx(txCborHex string) (string, error) {
	// Use raw TCP to avoid Go's strict "duplicate chunked TE" rejection
	txBytes, err := hex.DecodeString(txCborHex)
	if err != nil {
		return "", fmt.Errorf("invalid tx hex: %w", err)
	}

	conn, err := net.DialTimeout("tcp", "localhost:10000", 10*time.Second)
	if err != nil {
		return "", fmt.Errorf("connect: %w", err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(30 * time.Second))

	// Write raw HTTP request
	var reqBuf bytes.Buffer
	fmt.Fprintf(&reqBuf, "POST /local-cluster/api/tx/submit HTTP/1.1\r\n")
	fmt.Fprintf(&reqBuf, "Host: localhost:10000\r\n")
	fmt.Fprintf(&reqBuf, "Content-Type: application/cbor\r\n")
	fmt.Fprintf(&reqBuf, "Content-Length: %d\r\n", len(txBytes))
	fmt.Fprintf(&reqBuf, "Connection: close\r\n")
	fmt.Fprintf(&reqBuf, "\r\n")
	reqBuf.Write(txBytes)

	if _, err := conn.Write(reqBuf.Bytes()); err != nil {
		return "", fmt.Errorf("write: %w", err)
	}

	// Read response
	reader := bufio.NewReader(conn)
	// Read status line
	statusLine, err := reader.ReadString('\n')
	if err != nil {
		return "", fmt.Errorf("read status: %w", err)
	}
	parts := strings.SplitN(strings.TrimSpace(statusLine), " ", 3)
	if len(parts) < 2 {
		return "", fmt.Errorf("malformed status: %s", statusLine)
	}

	// Read headers until empty line
	var contentLength int
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			return "", fmt.Errorf("read header: %w", err)
		}
		line = strings.TrimSpace(line)
		if line == "" {
			break
		}
		if strings.HasPrefix(strings.ToLower(line), "content-length:") {
			fmt.Sscanf(strings.TrimSpace(line[15:]), "%d", &contentLength)
		}
	}

	// Read body
	var body []byte
	if contentLength > 0 {
		body = make([]byte, contentLength)
		_, err = io.ReadFull(reader, body)
	} else {
		body, err = io.ReadAll(reader)
	}
	if err != nil && err != io.EOF {
		return "", fmt.Errorf("read body: %w", err)
	}

	statusCode := parts[1]
	if statusCode != "200" && statusCode != "202" {
		return "", fmt.Errorf("submit failed (%s): %s", statusCode, string(body))
	}

	txHash := strings.TrimSpace(string(body))
	txHash = strings.Trim(txHash, "\"")
	return txHash, nil
}

func devkitGetTx(txHash string) (map[string]interface{}, error) {
	resp, err := http.Get(devkitURL + "/txs/" + txHash)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("get tx failed (%d)", resp.StatusCode)
	}
	var tx map[string]interface{}
	if err := json.NewDecoder(resp.Body).Decode(&tx); err != nil {
		return nil, err
	}
	return tx, nil
}

func waitForBlock() {
	time.Sleep(3 * time.Second)
}

func skipIfNoDevKit(t *testing.T) {
	t.Helper()
	if !devkitAvailable() {
		t.Skip("Yaci DevKit not available on port 10000")
	}
}

func fundSender(t *testing.T, ada int) *AccountInfo {
	t.Helper()
	account, err := bridge.Account.Create(Testnet)
	if err != nil {
		t.Fatalf("create account: %v", err)
	}
	if err := devkitTopup(account.BaseAddress, ada); err != nil {
		t.Fatalf("topup: %v", err)
	}
	waitForBlock()
	return account
}

func totalLovelace(utxos []map[string]interface{}) int64 {
	var total int64
	for _, u := range utxos {
		amounts, ok := u["amount"].([]interface{})
		if !ok {
			continue
		}
		for _, a := range amounts {
			am, ok := a.(map[string]interface{})
			if !ok {
				continue
			}
			if am["unit"] == "lovelace" {
				switch q := am["quantity"].(type) {
				case float64:
					total += int64(q)
				case string:
					var v int64
					fmt.Sscanf(q, "%d", &v)
					total += v
				}
			}
		}
	}
	return total
}

// --- Integration Tests ---

func TestIntegrationSimpleADATransfer(t *testing.T) {
	skipIfNoDevKit(t)
	devkitReset()
	waitForBlock()

	sender := fundSender(t, 150)
	receiver, _ := bridge.Account.Create(Testnet)

	utxos, err := devkitGetUtxos(sender.BaseAddress)
	if err != nil {
		t.Fatalf("get utxos: %v", err)
	}
	pp, err := devkitGetProtocolParams()
	if err != nil {
		t.Fatalf("get pp: %v", err)
	}

	yaml := quickTxYaml(sender.BaseAddress, receiver.BaseAddress, "5000000")
	result, err := bridge.QuickTx.Build(yaml, utxos, pp)
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	assertTxResult(t, result)

	signedTx, err := bridge.Account.SignTx(sender.Mnemonic, Testnet, 0, 0, result.TxCbor)
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	txHash, err := devkitSubmitTx(signedTx)
	if err != nil {
		t.Fatalf("submit: %v", err)
	}
	if txHash == "" {
		t.Fatal("empty tx hash from submit")
	}

	waitForBlock()
	receiverUtxos, err := devkitGetUtxos(receiver.BaseAddress)
	if err != nil {
		t.Fatalf("get receiver utxos: %v", err)
	}
	if total := totalLovelace(receiverUtxos); total != 5_000_000 {
		t.Errorf("expected 5 ADA (5000000), got %d lovelace", total)
	}
}

func TestIntegrationMultipleReceivers(t *testing.T) {
	skipIfNoDevKit(t)
	devkitReset()
	waitForBlock()

	sender := fundSender(t, 150)
	r1, _ := bridge.Account.Create(Testnet)
	r2, _ := bridge.Account.Create(Testnet)

	utxos, err := devkitGetUtxos(sender.BaseAddress)
	if err != nil {
		t.Fatalf("get utxos: %v", err)
	}
	pp, err := devkitGetProtocolParams()
	if err != nil {
		t.Fatalf("get pp: %v", err)
	}

	yaml := fmt.Sprintf(`
version: 1.0
transaction:
  - tx:
      from: %s
      intents:
        - type: payment
          address: %s
          amounts:
            - unit: lovelace
              quantity: "3000000"
        - type: payment
          address: %s
          amounts:
            - unit: lovelace
              quantity: "2000000"
`, sender.BaseAddress, r1.BaseAddress, r2.BaseAddress)

	result, err := bridge.QuickTx.Build(yaml, utxos, pp)
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	signedTx, _ := bridge.Account.SignTx(sender.Mnemonic, Testnet, 0, 0, result.TxCbor)
	if _, err := devkitSubmitTx(signedTx); err != nil {
		t.Fatalf("submit: %v", err)
	}

	waitForBlock()
	if total := totalLovelace(mustUtxos(t, r1.BaseAddress)); total != 3_000_000 {
		t.Errorf("expected 3 ADA for r1, got %d", total)
	}
}

func TestIntegrationInsufficientFunds(t *testing.T) {
	skipIfNoDevKit(t)
	devkitReset()
	waitForBlock()

	sender := fundSender(t, 2)
	receiver, _ := bridge.Account.Create(Testnet)

	utxos, _ := devkitGetUtxos(sender.BaseAddress)
	pp, _ := devkitGetProtocolParams()

	yaml := quickTxYaml(sender.BaseAddress, receiver.BaseAddress, "100000000")
	if _, err := bridge.QuickTx.Build(yaml, utxos, pp); err == nil {
		t.Fatal("expected insufficient funds error")
	}
}

func mustUtxos(t *testing.T, address string) []map[string]interface{} {
	t.Helper()
	utxos, err := devkitGetUtxos(address)
	if err != nil {
		t.Fatalf("get utxos: %v", err)
	}
	return utxos
}
