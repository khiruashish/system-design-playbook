#!/bin/bash
# Search inside PostgreSQL 16 (:5543): LIKE scans vs pg_trgm, full-text search (tsvector/GIN, ts_rank), autocomplete, typo tolerance, facets.
# 200,000 product listings from ExportCatalog.java (many sellers listing the same items).
set -e
cd /tmp/searchlab
export PGOPTIONS="-c client_min_messages=warning"
PSQL="psql -h localhost -p 5543 -U postgres -d orders -qAt -v ON_ERROR_STOP=1"
$PSQL <<'SQL' >/dev/null
create extension if not exists pg_trgm;
drop table if exists product;
create table product(id int primary key, type text, name text, brand text, category text, size text, popularity int, in_stock boolean, price_paise int);
\copy product from 'products.csv' with (format csv, header)
alter table product add column doc tsvector generated always as (
  setweight(to_tsvector('english', name), 'A') || setweight(to_tsvector('simple', brand || ' ' || category), 'B')) stored;
vacuum analyze product;
SQL
t() {   # best of 3 server-side ms
  for i in 1 2 3; do printf '\\timing on\n%s\n' "$1" | $PSQL 2>&1 | grep -o 'Time: [0-9.]*' | cut -d' ' -f2; done | sort -n | head -1
}
rows() { $PSQL -c "select count(*) from ($1) x"; }

echo "== 1. Substring search: LIKE '%paneer%'"
Q="select id from product where name ilike '%paneer%'"
printf "   no index (seq scan)        : %8s ms, %s rows\n" "$(t "$Q")" "$(rows "$Q")"
$PSQL -c "create index product_name_trgm on product using gin (name gin_trgm_ops)"
printf "   GIN trigram index          : %8s ms, %s rows   (index %s)\n" "$(t "$Q")" "$(rows "$Q")" "$($PSQL -c "select pg_size_pretty(pg_relation_size('product_name_trgm'))")"

echo "== 2. Full-text search: tsvector (english stemming on name, weight A; brand+category weight B), GIN, ts_rank"
$PSQL -c "create index product_doc on product using gin (doc)"
for q in "fresh paneer" "tomatoes" "tomatos" "masala chips" "आटा" "aata"; do
  Q="select id from product where doc @@ websearch_to_tsquery('english', '$q')"
  printf "   %-14s → %-38s %7s ms, %6s rows\n" "'$q'" "$($PSQL -c "select coalesce(nullif(websearch_to_tsquery('english', '$q')::text, ''), '(empty: no lexemes)')")" "$(t "$Q order by ts_rank(doc, websearch_to_tsquery('english', '$q')) desc limit 10")" "$(rows "$Q")"
done

echo "== 3. Autocomplete and typos"
Q="select distinct name from product where doc @@ to_tsquery('english', 'sham:*') limit 5"
printf "   prefix 'sham:*'            : %8s ms → %s\n" "$(t "$Q")" "$($PSQL -c "$Q" | paste -sd'|')"
Q="select name, round(word_similarity('shampo', name)::numeric, 2) from (select distinct name from product where 'shampo' <% name) s order by 2 desc, 1 limit 3"
printf "   word_similarity 'shampo'    : %8s ms → %s\n" "$(t "$Q")" "$($PSQL -c "$Q" | paste -sd'|')"
Q="select name, round(word_similarity('toor daal', name)::numeric, 2) from (select distinct name from product where 'toor daal' <% name) s order by 2 desc, 1 limit 3"
printf "   word_similarity 'toor daal' : %8s ms → %s\n" "$(t "$Q")" "$($PSQL -c "$Q" | paste -sd'|')"

echo "== 4. Facets for 'masala' in one round trip (grouping sets)"
Q="select coalesce(category, '*') as category, coalesce(brand, '*') as brand, count(*) from product where doc @@ websearch_to_tsquery('english', 'masala') group by grouping sets ((category), (brand)) order by 3 desc limit 6"
printf "   %8s ms → %s\n" "$(t "$Q")" "$($PSQL -F: -c "$Q" | paste -sd' ')"

echo "== 5. Ranking with business signals: text rank × popularity, in-stock first"
Q="select name, size, in_stock, popularity from product where doc @@ websearch_to_tsquery('english', 'paneer') order by in_stock desc, ts_rank(doc, websearch_to_tsquery('english', 'paneer')) * ln(2 + popularity) desc limit 3"
printf "   %8s ms → %s\n" "$(t "$Q")" "$($PSQL -F' ' -c "$Q" | paste -sd'|')"
