# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

"""Run the ElastiCache Integrations DynamoDB E2E scenarios against a live cache and table.

1. Cache hit: a second read returns the item after it was deleted from DynamoDB.
2. Concurrent reads: parallel cold misses on distinct keys each get their own item back, then
   parallel reads return every item from the cache after the origin deletes.
3. Query: a Query through ElastiCache Integrations returns the seeded item.
4. Write pass-through: a PutItem through ElastiCache Integrations is visible to a consistent origin read.
5. Origin error: a GetItem on a missing table raises ResourceNotFoundException.
6. Large binary item: a ~380 KB item with binary and non-ASCII attributes survives a cache miss
   and a cache hit byte for byte.
7. Batch reads and Scan: BatchGetItem and a filtered Scan through ElastiCache Integrations return every seeded item.
8. Delete pass-through: DeleteItem and BatchWriteItem through ElastiCache Integrations change DynamoDB.
"""

from __future__ import annotations

import os
import socket
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import boto3
from botocore.exceptions import ClientError

import aws_elasticache_integration_client


def main() -> None:
    load_env(Path(__file__).resolve().parents[2] / ".env")

    cache_host = env("ELASTICACHE_CACHE_HOST", "localhost")
    cache_port = positive_int_env("ELASTICACHE_CACHE_PORT", 6379)
    cache_name = env("ELASTICACHE_CACHE_NAME", "elasticache-integrations-e2e-cache")
    cache_user_id = os.environ.get("ELASTICACHE_CACHE_USER_ID")
    tunnel_host = os.environ.get("ELASTICACHE_TUNNEL_HOST")
    ca_cert = os.environ.get("ELASTICACHE_CA_CERT")
    request_timeout_ms = round(
        positive_float_env("ELASTICACHE_REQUEST_TIMEOUT_SECONDS", 5.0) * 1000
    )
    concurrency = positive_int_env("ELASTICACHE_CONCURRENCY", 16)
    # Defaults to us-east-1 when AWS_REGION is unset.
    region = env("AWS_REGION", "us-east-1")
    table_name = env("ELASTICACHE_TABLE_NAME", "ElastiCacheIntegrationsTestTable")
    # A fresh ID per run forces the first ElastiCache Integrations read to be a cache miss and keeps
    # concurrent runs from deleting each other's items.
    run_id = env("ELASTICACHE_ITEM_ID", f"elasticache-integrations-e2e-{uuid.uuid4()}")

    origin = boto3.client("dynamodb", region_name=region)
    original_getaddrinfo = socket.getaddrinfo
    cached_ddb = None
    seeded: list[str] = []
    try:
        if tunnel_host:
            socket.getaddrinfo = lambda host, *args, **kwargs: original_getaddrinfo(
                tunnel_host if host == cache_host else host,
                *args,
                **kwargs,
            )

        cached_ddb = boto3.client("dynamodb", region_name=region)
        aws_elasticache_integration_client.install(
            client=cached_ddb,
            endpoint=f"{cache_host}:{cache_port}",
            cache_name=cache_name,
            user_id=cache_user_id,
            ca_certs=ca_cert,
            client_configuration=aws_elasticache_integration_client.ElastiCacheIntegrationsClientConfiguration(
                cache_request_timeout=request_timeout_ms
            ),
        )

        scenario_cache_hit(origin, cached_ddb, table_name, run_id, seeded)
        scenario_concurrent_reads(origin, cached_ddb, table_name, run_id, concurrency, seeded)
        scenario_query(origin, cached_ddb, table_name, run_id, seeded)
        scenario_write_pass_through(origin, cached_ddb, table_name, run_id, seeded)
        scenario_origin_error(cached_ddb, run_id)
        scenario_large_binary_item(origin, cached_ddb, table_name, run_id, seeded)
        scenario_batch_reads_and_scan(origin, cached_ddb, table_name, run_id, seeded)
        scenario_delete_pass_through(origin, cached_ddb, table_name, run_id, seeded)
        print("SUCCESS: all ElastiCache Integrations E2E scenarios passed.")
    finally:
        for item_id in seeded:
            origin.delete_item(TableName=table_name, Key={"id": {"S": item_id}})
        if cached_ddb is not None:
            cached_ddb.close()
        origin.close()
        socket.getaddrinfo = original_getaddrinfo


def scenario_cache_hit(origin, cached_ddb, table_name: str, run_id: str, seeded: list) -> None:
    print("== Cache hit after origin delete")
    item_id = f"{run_id}-hit"
    item = seed(origin, table_name, item_id, seeded)
    request = get_request(table_name, item_id)

    first = timed_get(cached_ddb, request, "GetItem #1")
    assert_item(first.get("Item"), item)

    delete_from_origin(origin, table_name, item_id)

    second = timed_get(cached_ddb, request, "GetItem #2 (after DynamoDB delete)")
    assert_item(second.get("Item"), item)
    print(
        "PASS: ElastiCache Integrations returned the cached item after it was deleted from DynamoDB."
    )


def scenario_concurrent_reads(
    origin, cached_ddb, table_name: str, run_id: str, concurrency: int, seeded: list
) -> None:
    print(f"== {concurrency} concurrent reads on distinct keys")
    items = [
        seed(origin, table_name, f"{run_id}-concurrent-{index}", seeded, quiet=True)
        for index in range(concurrency)
    ]

    def read_all(label: str) -> None:
        started = time.perf_counter()
        with ThreadPoolExecutor(max_workers=concurrency) as pool:
            responses = list(
                pool.map(
                    lambda item: cached_ddb.get_item(**get_request(table_name, item["id"]["S"])),
                    items,
                )
            )
        elapsed_ms = (time.perf_counter() - started) * 1000
        for item, response in zip(items, responses):
            # A cross-completed response would carry another key's item.
            assert_item(response.get("Item"), item)
        print(f"{label}: {len(items)} GetItems in {elapsed_ms:.1f} ms, each matched its key")

    read_all("Cold reads")
    for item in items:
        origin.delete_item(TableName=table_name, Key={"id": item["id"]})
    read_all("Reads after DynamoDB delete")
    print("PASS: concurrent responses matched their requests and were served from the cache.")


def scenario_query(origin, cached_ddb, table_name: str, run_id: str, seeded: list) -> None:
    print("== Query through ElastiCache Integrations")
    item_id = f"{run_id}-query"
    item = seed(origin, table_name, item_id, seeded)
    response = cached_ddb.query(
        TableName=table_name,
        KeyConditionExpression="id = :id",
        ExpressionAttributeValues={":id": {"S": item_id}},
    )
    if response.get("Items") != [item]:
        raise RuntimeError(f"Unexpected Query response: {response.get('Items')!r}")
    print("PASS: Query returned the seeded item.")


def scenario_write_pass_through(
    origin, cached_ddb, table_name: str, run_id: str, seeded: list
) -> None:
    print("== PutItem through ElastiCache Integrations")
    item_id = f"{run_id}-write"
    item = new_item(item_id)
    seeded.append(item_id)
    cached_ddb.put_item(TableName=table_name, Item=item)
    stored = origin.get_item(**get_request(table_name, item_id), ConsistentRead=True)
    assert_item(stored.get("Item"), item)
    print(
        "PASS: a consistent DynamoDB read returned the item written through ElastiCache Integrations."
    )


def scenario_origin_error(cached_ddb, run_id: str) -> None:
    print("== Origin error on a missing table")
    missing_table = f"{run_id}-missing-table"
    try:
        cached_ddb.get_item(**get_request(missing_table, "missing"))
    except ClientError as error:
        code = error.response["Error"]["Code"]
        if code != "ResourceNotFoundException":
            raise RuntimeError(f"Expected ResourceNotFoundException, got {code}") from error
        print("PASS: GetItem on a missing table raised ResourceNotFoundException.")
        return
    raise RuntimeError(f"GetItem on {missing_table} succeeded")


def scenario_large_binary_item(
    origin, cached_ddb, table_name: str, run_id: str, seeded: list
) -> None:
    print("== Large binary item")
    item_id = f"{run_id}-large"
    item = large_item(item_id)
    payload_size = len(item["payload"]["B"])
    print(f"Seeding {table_name}/{item_id} ({payload_size} byte payload) in DynamoDB...")
    origin.put_item(TableName=table_name, Item=item)
    seeded.append(item_id)
    request = get_request(table_name, item_id)

    assert_item(timed_get(cached_ddb, request, "GetItem #1").get("Item"), item)
    delete_from_origin(origin, table_name, item_id)
    second = timed_get(cached_ddb, request, "GetItem #2 (after DynamoDB delete)")
    assert_item(second.get("Item"), item)
    print("PASS: the large binary item matched byte for byte on the miss and the hit.")


def scenario_batch_reads_and_scan(
    origin, cached_ddb, table_name: str, run_id: str, seeded: list
) -> None:
    print("== BatchGetItem and Scan through ElastiCache Integrations")
    prefix = f"{run_id}-batch-"
    items = [seed(origin, table_name, f"{prefix}{index}", seeded, quiet=True) for index in range(3)]

    response = cached_ddb.batch_get_item(
        RequestItems={table_name: {"Keys": [{"id": item["id"]} for item in items]}}
    )
    if response.get("UnprocessedKeys"):
        raise RuntimeError(f"BatchGetItem left keys unprocessed: {response['UnprocessedKeys']!r}")
    assert_items(response["Responses"][table_name], items)

    response = cached_ddb.scan(
        TableName=table_name,
        FilterExpression="begins_with(id, :prefix)",
        ExpressionAttributeValues={":prefix": {"S": prefix}},
    )
    assert_items(response["Items"], items)
    print("PASS: BatchGetItem and Scan returned every seeded item.")


def scenario_delete_pass_through(
    origin, cached_ddb, table_name: str, run_id: str, seeded: list
) -> None:
    print("== DeleteItem and BatchWriteItem through ElastiCache Integrations")
    deleted = seed(origin, table_name, f"{run_id}-delete", seeded)
    cached_ddb.delete_item(TableName=table_name, Key={"id": deleted["id"]})
    assert_absent(origin, table_name, deleted["id"]["S"])

    batch_deleted = seed(origin, table_name, f"{run_id}-batch-delete", seeded)
    batch_put = new_item(f"{run_id}-batch-put")
    seeded.append(batch_put["id"]["S"])
    cached_ddb.batch_write_item(
        RequestItems={
            table_name: [
                {"PutRequest": {"Item": batch_put}},
                {"DeleteRequest": {"Key": {"id": batch_deleted["id"]}}},
            ]
        }
    )
    stored = origin.get_item(**get_request(table_name, batch_put["id"]["S"]), ConsistentRead=True)
    assert_item(stored.get("Item"), batch_put)
    assert_absent(origin, table_name, batch_deleted["id"]["S"])
    print(
        "PASS: consistent DynamoDB reads reflected the deletes and the put written through ElastiCache Integrations."
    )


def new_item(item_id: str) -> dict:
    return {
        "id": {"S": item_id},
        "name": {"S": "elasticache-integrations-widget"},
        "description": {"S": "e2e test item for ElastiCache Integrations caching"},
    }


def large_item(item_id: str) -> dict:
    # DynamoDB caps an item at 400 KB, so this stays just under the limit.
    payload = os.urandom(380_000)
    return {
        "id": {"S": item_id},
        "payload": {"B": payload},
        "text": {"S": "caf\u00e9 \u2713 \u65e5\u672c\u8a9e \U0001f680"},
    }


def get_request(table_name: str, item_id: str) -> dict:
    return {"TableName": table_name, "Key": {"id": {"S": item_id}}}


def seed(origin, table_name: str, item_id: str, seeded: list, quiet: bool = False) -> dict:
    item = new_item(item_id)
    if not quiet:
        print(f"Seeding {table_name}/{item_id} directly in DynamoDB...")
    origin.put_item(TableName=table_name, Item=item)
    seeded.append(item_id)
    return item


def delete_from_origin(origin, table_name: str, item_id: str) -> None:
    print(f"Deleting {table_name}/{item_id} directly from DynamoDB...")
    origin.delete_item(**get_request(table_name, item_id))
    assert_absent(origin, table_name, item_id)
    print("Confirmed the item no longer exists in DynamoDB.")


def assert_absent(origin, table_name: str, item_id: str) -> None:
    response = origin.get_item(**get_request(table_name, item_id), ConsistentRead=True)
    if "Item" in response:
        raise RuntimeError(f"DynamoDB still contains the item: {response['Item']!r}")


def timed_get(dynamodb, request: dict, label: str) -> dict:
    started = time.perf_counter()
    response = dynamodb.get_item(**request)
    elapsed_ms = (time.perf_counter() - started) * 1000
    status = response["ResponseMetadata"]["HTTPStatusCode"]
    print(f"{label}: HTTP {status} in {elapsed_ms:.1f} ms")
    return response


def assert_item(actual: dict | None, expected: dict) -> None:
    if actual != expected:
        raise RuntimeError(f"Unexpected GetItem response: {actual!r}")


def assert_items(actual: list, expected: list) -> None:
    by_id = {item["id"]["S"]: item for item in actual}
    if len(actual) != len(expected) or any(by_id.get(item["id"]["S"]) != item for item in expected):
        raise RuntimeError(f"Unexpected items: {actual!r}")


def env(name: str, default: str) -> str:
    return os.environ.get(name) or default


def load_env(path: Path) -> None:
    if not path.is_file():
        return
    for raw_line in path.read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip("\"'"))


def positive_int_env(name: str, default: int) -> int:
    raw = env(name, str(default))
    value = int(raw)
    if value <= 0:
        raise ValueError(f"{name} must be positive, got {raw}")
    return value


def positive_float_env(name: str, default: float) -> float:
    raw = env(name, str(default))
    value = float(raw)
    if value <= 0:
        raise ValueError(f"{name} must be positive, got {raw}")
    return value


if __name__ == "__main__":
    main()
