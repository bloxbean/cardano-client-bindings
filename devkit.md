Devkit Admin URLs at port  10000:

- POST /local-cluster/api/admin/devnet/reset - Reset devnet
- POST /local-cluster/api/addresses/topup - Fund addresses ({"address": "...", "adaAmount": 100})
- GET /local-cluster/api/addresses/{address}/utxos - Get UTXOs (same JSON format as CCL's Utxo model)
- GET /local-cluster/api/epochs/parameters - Get protocol params (same JSON format as CCL's ProtocolParams)
- POST /local-cluster/api/tx/submit - Submit tx CBOR (application/cbor)
- GET /local-cluster/api/txs/{hash} - Get tx by hash (waits for block confirmation)               
