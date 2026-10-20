# Ch 23 labs: Security Architecture

All defensive: each lab hardens a control and asserts that bad inputs are refused. Java 21 (no external deps except a KMS for L4).
For L4, OpenBao 2.1 (an open-source Vault) provides the KMS transit engine.

```bash
# L4 only: start a dev KMS and enable transit
bao server -dev -dev-root-token-id=root -dev-listen-address=127.0.0.1:8210 &
export BAO=http://127.0.0.1:8210 TOK=root
curl -s -H "X-Vault-Token: $TOK" -X POST $BAO/v1/sys/mounts/transit -d '{"type":"transit"}'
curl -s -H "X-Vault-Token: $TOK" -X POST $BAO/v1/transit/keys/orders-dek -d '{"type":"aes256-gcm96"}'
```

| Level | Run | Output |
|---|---|---|
| L1 JWT validation, JWKS rotation, refresh reuse detection | `java TokensL1.java` | `tokens_out.txt` |
| L2 OTP + OAuth Code/PKCE control conformance (real HTTP) | `java -Dsun.net.httpserver.nodelay=true LoginL2.java` | `login_out.txt` |
| L3 RBAC vs ABAC vs ReBAC, IDOR negative control | `java AuthzL3.java` | `authz_out.txt` |
| L4 envelope encryption, KEK rotation, crypto-shredding | `BAO=… TOK=… java CryptoL4.java` | `crypto_out.txt` |

L1 and L4 write out crypto by hand to *teach*; production uses vetted libraries (Nimbus/jose4j, Tink/libsodium) — see the VERIFY snippets in the chapter. Timings vary by machine.
