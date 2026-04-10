# MongoDB Test Datasets & Benchmarks for query-lens

Reference for available MongoDB log dumps, datasets, and benchmark tools to test the query-lens rule engine.

## Rule Quick Reference

| Rule | Trigger Conditions |
|---|---|
| **CollscanMissingIndexRule** (HIGH) | `planSummary == "COLLSCAN"`, `docsExamined >= 500`, `durationMillis >= 100`, namespace appears `>= 2` times in window |
| **PoorIndexSelectivityRule** (MEDIUM) | `planSummary` contains `"IXSCAN"`, `keysExamined / nreturned >= 50`, `durationMillis >= 100`, `>= 2` occurrences per namespace |
| **RetryStormRule** (HIGH) | `>= 10` queries to the same namespace in window, any plan |

---

## 1. Real Log Fixtures (Fastest to Use)

### 1.1 simagix/hatchet — bundled sample logv2 file
- **URL**: https://github.com/simagix/hatchet
- **File**: `logs/sample-mongod.log.gz`
- **Format**: MongoDB logv2 JSON, gzip — pipe directly into query-lens ingestion
- **Rules**: CollscanMissingIndexRule, PoorIndexSelectivityRule
- **Notes**: Closest ready-to-use logv2 fixture available. Clone repo and use the gz file as-is.

### 1.2 Confirmed real logv2 line (copy-paste unit test fixture)
```json
{"t":{"$date":"2020-06-16T14:42:28.148+02:00"},"s":"I","c":"COMMAND","id":51803,"ctx":"conn16","msg":"Slow query","attr":{"type":"command","ns":"video.movies","appName":"MongoDB Shell","command":{"find":"movies","filter":{}},"planSummary":"COLLSCAN","keysExamined":0,"docsExamined":57989,"nreturned":57989,"durationMillis":171}}
```
Directly triggers CollscanMissingIndexRule (`docsExamined=57989 >= 500`, `durationMillis=171 >= 100`).

### 1.3 simagix/keyhole — redacted log examples with COLLSCAN
- **URL**: https://github.com/simagix/keyhole/blob/master/loginfo.md
- **Format**: Redacted real log lines, including entries with `docsExamined:121741412`
- **Rules**: CollscanMissingIndexRule
- **Notes**: Copy-paste material for unit test fixtures. Very high `docsExamined` values present.

### 1.4 rueckstiess/mtools — test fixture logs
- **URL**: https://github.com/rueckstiess/mtools
- **Format**: Mix of pre-4.4 text format and partial logv2 JSON in `test/` directories
- **Rules**: CollscanMissingIndexRule (mlogfilter has explicit `--collscan` flag, implying test logs contain COLLSCAN entries)
- **Notes**: Older format logs need conversion for logv2 parsing; useful as reference for query shape variety.

### 1.5 mongodb-ps/ce-mgotools — test corpus
- **URL**: https://github.com/mongodb-ps/ce-mgotools
- **Format**: Mixed text + logv2 JSON fixtures across MongoDB versions 3.x–6.x in `test/` or `testdata/`
- **Rules**: CollscanMissingIndexRule, PoorIndexSelectivityRule
- **Notes**: Spiritual successor to mtools with explicit logv2 support.

---

## 2. MongoDB Atlas Sample Datasets

Load locally via `mongoimport` from https://github.com/neelabalan/mongodb-sample-dataset. Start `mongod` with `slowOpThresholdMs=0` and run queries to generate logv2 slow query entries.

| Dataset | Collection | Docs | Best Rule | Query to trigger |
|---|---|---|---|---|
| `sample_mflix` | `movies` | ~21k | CollscanMissingIndexRule | `db.movies.find({genres: "Drama"})` — no index on `genres` |
| `sample_airbnb` | `listingsAndReviews` | ~5.5k | PoorIndexSelectivityRule | Add index on `room_type` (3 distinct values), query by `room_type` → ratio ≈ 1850:1 |
| `sample_training` | `grades` | ~100k | CollscanMissingIndexRule | `db.grades.find({student_id: 5})` without index |
| `sample_analytics` | `transactions` | ~1.75M | PoorIndexSelectivityRule | Index on `transaction_code` (low cardinality) |
| `sample_supplies` | `sales` | ~5k | RetryStormRule | Rapid repeated queries by `storeLocation` |
| `sample_weatherdata` | `data` | ~10k | CollscanMissingIndexRule | Geo queries without 2dsphere index |

**Local mirror**: https://github.com/neelabalan/mongodb-sample-dataset  
**Atlas docs**: https://www.mongodb.com/docs/atlas/sample-data/

---

## 3. Benchmark Suites (Generate Logs from Live Workloads)

Configure `mongod` with `--setParameter slowOpThresholdMs=0 --setParameter profilingMode=all` before running any benchmark to capture all operations in logv2 format.

### 3.1 mongodb/genny — official MongoDB workload generator
- **URL**: https://github.com/mongodb/genny
- **Format**: YAML workload definitions + C++ actors; run against live `mongod`
- **Rules**: All three — `CollectionScan` actor for COLLSCAN, `InsertRemove` for low-cardinality index patterns, high-concurrency phases for RetryStorm
- **Notes**: MongoDB's own internal CI benchmark. Most authoritative source of realistic workloads.

### 3.2 brianfrankcooper/YCSB — Yahoo Cloud Serving Benchmark
- **URL**: https://github.com/brianfrankcooper/YCSB
- **MongoDB binding**: `mongodb/README.md` in the repo
- **Format**: Java; 6 workloads A–F with configurable read/write/scan ratios
- **Rules**:
  - Workload E (range scans) without range index → CollscanMissingIndexRule
  - Workload A/B with low-cardinality indexed field → PoorIndexSelectivityRule
  - Workload F (read-modify-write) at high thread counts → RetryStormRule
- **Notes**: Default 10M records; set `slowms: 0` before running.

### 3.3 mongodb-labs/py-tpcc — TPC-C port
- **URL**: https://github.com/mongodb-labs/py-tpcc
- **Format**: Python; models `customers`, `orders`, `items`, `warehouse` (standard TPC-C schema)
- **Rules**: RetryStormRule (`new-order` and `payment` transactions burst to same collections), PoorIndexSelectivityRule (`warehouse`/`district` status fields are low-cardinality)
- **Reference**: VLDB 2019 — "Adapting TPC-C Benchmark to Measure Performance of Multi-Document Transactions in MongoDB" https://www.vldb.org/pvldb/vol12/p2254-kamsky.pdf

### 3.4 nosqlbench/nosqlbench — pluggable NoSQL benchmark
- **URL**: https://github.com/nosqlbench/nosqlbench
- **MongoDB driver docs**: https://docs.nosqlbench.io/reference/drivers/mongodb/
- **Format**: YAML workload definitions using `db.runCommand` API
- **Rules**: All three — workloads directly specify unindexed queries, low-cardinality filters, high-frequency namespace hits

### 3.5 mongodb/mongo-perf — micro-benchmark harness
- **URL**: https://github.com/mongodb/mongo-perf
- **Format**: JavaScript via `benchrun.py`; many deliberately unindexed test cases
- **Rules**: CollscanMissingIndexRule, PoorIndexSelectivityRule
- **Notes**: Designed for MongoDB server developers; requires legacy `mongo` shell (≤5.0) for `benchRun()`.

---

## 4. Synthetic Data Generators (Controlled Rule Triggering)

### 4.1 feliixx/mgodatagen — Go CLI, millions of docs
- **URL**: https://github.com/feliixx/mgodatagen
- **Format**: JSON config → direct MongoDB insert
- **Best for**: PoorIndexSelectivityRule — use `"maxDistinctValue": 2` on a field + add index → guaranteed `keysExamined / nreturned >> 50`
- **Example config snippet**:
  ```json
  {"fieldName": "status", "type": "enum", "values": ["active","inactive"], "maxDistinctValue": 2}
  ```
  Add index on `status`, query by `status` → ratio ≈ 500:1 on 1000+ docs.

### 4.2 rueckstiess/mgeneratejs — template-based document generator
- **URL**: https://github.com/rueckstiess/mgeneratejs
- **Format**: JSON template → stdout → `mongoimport`
- **Best for**: CollscanMissingIndexRule — generate collection with no index, query any non-`_id` field
- **Example template**:
  ```json
  {"userId": {"$oid": ""}, "status": {"$enum": ["active","inactive"]}, "value": {"$number": {"min":1,"max":10000}}}
  ```

### 4.3 Percona-Lab/percona-load-generator-mongodb (PLGM)
- **URL**: https://github.com/Percona-Lab/percona-load-generator-mongodb
- **Format**: Python; continuous Find/Insert/Update/Delete/Aggregate/Upsert at configurable ratios and thread counts
- **Best for**: RetryStormRule — high thread count with Find ops against single namespace; combine with mgodatagen data

### 4.4 idealo/mongodb-performance-test
- **URL**: https://github.com/idealo/mongodb-performance-test
- **Format**: Java CLI; multithreaded insert/find/update/delete
- **Best for**: RetryStormRule — high-concurrency find against single namespace

---

## 5. Traffic Capture & Replay

### 5.1 mongodb-labs/mongoreplay
- **URL**: https://github.com/mongodb-labs/mongoreplay
- **Format**: Captures network wire traffic → BSON playback file; replays against any `mongod`
- **Rules**: All three — replaying production traffic against a QA `mongod` with profiling on generates authentic slow query logs
- **Notes**: Most realistic logs possible since they come from real production query shapes. Requires root/pcap access.

### 5.2 facebookarchive/flashback (archived)
- **URL**: https://github.com/facebookarchive/flashback
- **Format**: Records via `db.system.profile` (level 2) → Go replay engine
- **Rules**: All three
- **Notes**: Archived since 2018 but Go binary still compiles. The recording side exports to `db.system.profile`, which can be `mongoexport`-ed as a dataset.

---

## 6. Scenario Matrix

| Scenario | Dataset/Tool | Setup | Expected Rule |
|---|---|---|---|
| Cold start unit test | Confirmed logv2 line (§1.2) | Paste into test fixture | CollscanMissingIndexRule |
| Real log smoke test | simagix/hatchet `sample-mongod.log.gz` | Pipe through ingestion | CollscanMissingIndexRule, PoorIndexSelectivityRule |
| Controlled COLLSCAN | `sample_mflix.movies`, query `{genres:"Drama"}` | `mongoimport` + `slowms=0` | CollscanMissingIndexRule |
| Controlled poor selectivity | `sample_airbnb` + index on `room_type` | `mongoimport` + add index | PoorIndexSelectivityRule |
| Controlled retry storm | PLGM, 20 threads, single collection | `mongod` + PLGM | RetryStormRule |
| Guaranteed rule trigger | mgodatagen + PLGM | JSON config, see §4.1 | All three, configurable |
| Realistic e2e corpus | `sample_mflix` + `sample_airbnb` + YCSB | Full local setup | All three |
| Production-like | mongoreplay from prod capture | Requires pcap access | All three |

---

## 7. Recommended Setup for End-to-End Log Generation

```bash
# 1. Start mongod with all ops profiled
mongod --setParameter slowOpThresholdMs=0 --setParameter profilingMode=all --logpath mongod.log

# 2. Load sample_mflix
mongoimport --db sample_mflix --collection movies --file movies.json

# 3. Trigger COLLSCAN — no index on genres
mongosh --eval 'db.getSiblingDB("sample_mflix").movies.find({genres:"Drama"}).toArray()' --quiet

# 4. Trigger PoorIndexSelectivity — low-cardinality index on room_type
mongosh --eval '
  db.getSiblingDB("sample_airbnb").listingsAndReviews.createIndex({room_type:1});
  db.getSiblingDB("sample_airbnb").listingsAndReviews.find({room_type:"Private room"}).toArray();
' --quiet

# 5. Trigger RetryStorm — 15 rapid queries to same namespace
for i in {1..15}; do
  mongosh --eval 'db.getSiblingDB("sample_mflix").movies.findOne({year: 2000})' --quiet &
done; wait

# mongod.log now contains logv2 JSON for all three rule scenarios
```
