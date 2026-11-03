#!/bin/bash
# Protobuf wire format and schema evolution with the real protoc (no Java needed).
P=${PROTOC:-/opt/protoc/bin/protoc}
cd "$(dirname "$0")"
# a 12-item order in text format and the equivalent JSON (what a REST API would send)
{
  echo 'order_id: "ord_214_1a0de1f7a40_df6a5d04" customer_id: "cust-42" total_paise: 184750 status: PLACED placed_at_epoch_ms: 1790340802794'
  for i in $(seq 1 12); do echo "items { sku: \"SKU-$((10000 + i * 37))\" qty: $(( (i % 3) + 1 )) price_paise: $(( 4500 + i * 1250 )) }"; done
} > order.txt
python3 - > order.json <<'PY'
import json
items=[{"sku":f"SKU-{10000+i*37}","qty":(i%3)+1,"pricePaise":4500+i*1250} for i in range(1,13)]
print(json.dumps({"orderId":"ord_214_1a0de1f7a40_df6a5d04","customerId":"cust-42","items":items,"totalPaise":184750,"status":"PLACED","placedAtEpochMs":1790340802794},separators=(",",":")))
PY
$P --encode=grocery.order.v1.Order order_v1.proto < order.txt > order.bin
echo "== 1. size of the same 12-item order"
printf "   JSON (compact)     : %4d bytes, gzip %4d\n" $(wc -c < order.json) $(gzip -9c order.json | wc -c)
printf "   protobuf (binary)  : %4d bytes, gzip %4d\n" $(wc -c < order.bin) $(gzip -9c order.bin | wc -c)
echo "   the first bytes on the wire: $(head -c 24 order.bin | od -An -tx1 | tr -s ' ' | tr -d '\n')  (field 1 = tag 0a, then length, then the id)"

echo "== 2. forward compatibility: a v2 producer adds coupon_code (field 7) and renames order_id → id"
sed 's/order_id:/id:/' order.txt | sed 's/status: PLACED/status: PLACED coupon_code: "DIWALI50"/' > order_v2.txt
$P --encode=grocery.order.v1.Order order_v2.proto < order_v2.txt > order_v2.bin
echo "   old v1 consumer decodes the v2 bytes:"
$P --decode=grocery.order.v1.Order order_v1.proto < order_v2.bin | grep -vE '^items|^  |^}' | sed 's/^/     /'
echo "   → the rename is invisible (numbers, not names, are on the wire); the unknown field 7 is kept, not lost"

echo "== 3. backward compatibility: a new v2 consumer decodes old v1 bytes"
$P --decode=grocery.order.v1.Order order_v2.proto < order.bin | grep -vE '^items|^  |^}' | sed 's/^/     /'
echo "   → coupon_code is simply absent (default \"\"), so v2 code must treat empty as 'no coupon'"

echo "== 4. an INCOMPATIBLE change: total_paise int64 → string (same field number 4)"
$P --decode=grocery.order.v1.Order order_broken.proto < order.bin 2>&1 | grep -E 'total_paise|Failed|4:' | sed 's/^/     /'
echo "   → wire-type mismatch: the new schema sees no total_paise at all (it survives only as unknown field 4)."
echo "     Nothing fails; consumers silently read an empty total. Reserve the number and add a NEW field instead."
