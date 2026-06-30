package ccl

// Optional chain-data provider helpers.
//
// QuickTx.Build is offline by design: the caller supplies UTXOs and protocol parameters (and, for
// Plutus, execution units). These helpers are an optional convenience that fetch those inputs from a
// chain-data backend over HTTP, returning them in the shape Build already accepts — so the native
// library stays offline and provider-free, and the helpers are pure wrapper-side code using the
// standard net/http client.
//
// A provider implements two methods:
//
//	Utxos(address)     -> all UTXOs at the address (no selection — the bridge selects)
//	ProtocolParams()   -> protocol parameters
//
// Use one directly, or via QuickTxApi.BuildWithProvider:
//
//	provider := ccl.NewBlockfrostProvider(projectID, "preprod") // or ccl.NewYaciProvider("")
//	result, err := bridge.QuickTx.BuildWithProvider(yaml, provider, senderAddress)

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
)

// ChainDataProvider fetches the chain data QuickTx.Build needs. Implement it to plug in any backend
// (Blockfrost, Koios, Ogmios, Yaci DevKit, ...).
type ChainDataProvider interface {
	// Utxos returns all UTXOs at the address (the bridge selects internally; no selection needed).
	Utxos(address string) ([]map[string]interface{}, error)
	// ProtocolParams returns the current protocol parameters.
	ProtocolParams() (map[string]interface{}, error)
}

func httpGetJSON(url string, headers map[string]string, out interface{}) error {
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return err
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("GET %s failed: HTTP %d: %s", url, resp.StatusCode, string(body))
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

// YaciProvider is a ChainDataProvider backed by Yaci DevKit / yaci-store (Blockfrost-style REST).
// Its UTXO and protocol-parameter responses are already in the shape Build expects.
type YaciProvider struct {
	BaseURL string
}

const defaultYaciURL = "http://localhost:10000/local-cluster/api"

// NewYaciProvider returns a YaciProvider for the given base URL; pass "" for the local DevKit cluster.
func NewYaciProvider(baseURL string) *YaciProvider {
	if baseURL == "" {
		baseURL = defaultYaciURL
	}
	return &YaciProvider{BaseURL: strings.TrimRight(baseURL, "/")}
}

func (p *YaciProvider) Utxos(address string) ([]map[string]interface{}, error) {
	var utxos []map[string]interface{}
	err := httpGetJSON(fmt.Sprintf("%s/addresses/%s/utxos", p.BaseURL, address), nil, &utxos)
	return utxos, err
}

func (p *YaciProvider) ProtocolParams() (map[string]interface{}, error) {
	var pp map[string]interface{}
	err := httpGetJSON(p.BaseURL+"/epochs/parameters", nil, &pp)
	return pp, err
}

var blockfrostNetworkURLs = map[string]string{
	"mainnet": "https://cardano-mainnet.blockfrost.io/api/v0",
	"preprod": "https://cardano-preprod.blockfrost.io/api/v0",
	"preview": "https://cardano-preview.blockfrost.io/api/v0",
}

// BlockfrostProvider is a ChainDataProvider backed by the Blockfrost API. UTXOs are paginated 100 per
// page, and Blockfrost omits the owning address on each UTXO so it is injected.
type BlockfrostProvider struct {
	BaseURL   string
	ProjectID string
}

// NewBlockfrostProvider returns a provider for the given network ("mainnet"/"preprod"/"preview").
// Use NewBlockfrostProviderURL to point at a custom base URL.
func NewBlockfrostProvider(projectID, network string) (*BlockfrostProvider, error) {
	baseURL, ok := blockfrostNetworkURLs[network]
	if !ok {
		return nil, fmt.Errorf("unknown network %q; use NewBlockfrostProviderURL", network)
	}
	return NewBlockfrostProviderURL(projectID, baseURL), nil
}

// NewBlockfrostProviderURL returns a provider pointed at an explicit base URL (e.g. self-hosted).
func NewBlockfrostProviderURL(projectID, baseURL string) *BlockfrostProvider {
	return &BlockfrostProvider{BaseURL: strings.TrimRight(baseURL, "/"), ProjectID: projectID}
}

func (p *BlockfrostProvider) headers() map[string]string {
	return map[string]string{"project_id": p.ProjectID}
}

func (p *BlockfrostProvider) Utxos(address string) ([]map[string]interface{}, error) {
	var out []map[string]interface{}
	for page := 1; ; page++ {
		var items []map[string]interface{}
		url := fmt.Sprintf("%s/addresses/%s/utxos?count=100&page=%d", p.BaseURL, address, page)
		if err := httpGetJSON(url, p.headers(), &items); err != nil {
			return nil, err
		}
		if len(items) == 0 {
			break
		}
		for _, u := range items {
			// Blockfrost omits the owning address on each UTXO; Build needs it.
			if _, ok := u["address"]; !ok {
				u["address"] = address
			}
			out = append(out, u)
		}
		if len(items) < 100 {
			break
		}
	}
	return out, nil
}

func (p *BlockfrostProvider) ProtocolParams() (map[string]interface{}, error) {
	// Blockfrost's parameters are a superset of CCL's ProtocolParams; the native lib ignores
	// unknown fields, so the response passes through unchanged.
	var pp map[string]interface{}
	err := httpGetJSON(p.BaseURL+"/epochs/latest/parameters", p.headers(), &pp)
	return pp, err
}

// BuildWithProvider fetches chain data from the provider and builds, in one call — composing
// provider.Utxos(sender) + provider.ProtocolParams() with Build. The bridge stays offline; this only
// moves the optional HTTP fetch into wrapper code.
func (q *QuickTxApi) BuildWithProvider(yaml string, provider ChainDataProvider, sender string, execUnits ...interface{}) (*TxResult, error) {
	utxos, err := provider.Utxos(sender)
	if err != nil {
		return nil, fmt.Errorf("provider utxos: %w", err)
	}
	pp, err := provider.ProtocolParams()
	if err != nil {
		return nil, fmt.Errorf("provider protocol params: %w", err)
	}
	return q.Build(yaml, utxos, pp, execUnits...)
}
